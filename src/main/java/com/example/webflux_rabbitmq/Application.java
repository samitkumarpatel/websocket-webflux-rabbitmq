package com.example.webflux_rabbitmq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Delivery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.RabbitFlux;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.ReceiverOptions;
import reactor.rabbitmq.Sender;
import reactor.rabbitmq.SenderOptions;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@SpringBootApplication
public class Application {

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

	@Bean
	ConnectionFactory connectionFactory(org.springframework.amqp.rabbit.connection.CachingConnectionFactory cachingConnectionFactory) {
		return cachingConnectionFactory.getRabbitConnectionFactory();
	}

	@Bean
	Sender sender(ConnectionFactory connectionFactory) {
		return RabbitFlux.createSender(new SenderOptions().connectionFactory(connectionFactory));
	}

	@Bean
	Receiver receiver(ConnectionFactory connectionFactory) {
		return RabbitFlux.createReceiver(new ReceiverOptions().connectionFactory(connectionFactory));
	}

	@Bean
	RabbitAdmin rabbitAdmin(org.springframework.amqp.rabbit.connection.ConnectionFactory springConnectionFactory) {
		return new RabbitAdmin(springConnectionFactory);
	}

	@Bean
	UserHttpClient userHttpClient(WebClient.Builder webClientbuilder) {
		WebClientAdapter adapter = WebClientAdapter.create(WebClient.builder().build());
		HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter).build();
		return factory.createClient(UserHttpClient.class);
	}

	@Bean
	RouterFunction<ServerResponse> routerFunction(RouterHandler routerHandler) {
		return RouterFunctions
				.route()
				.GET("/users", routerHandler::fetchAllUser)
				.build();
	}
}

@Component
@RequiredArgsConstructor
class RouterHandler {
	private final RabbitAdmin rabbitAdmin;
	private final UserHttpClient userHttpClient;

	public Mono<ServerResponse> fetchAllUser(ServerRequest request) {
		return userHttpClient
				.allUser()
				.flatMap(ServerResponse.ok()::bodyValue);
	}
}

record User(String id, String name, String username, String email, String phone, String website) {}

@HttpExchange(url = "https://jsonplaceholder.typicode.com")
interface UserHttpClient {
	@GetExchange("/users")
	Mono<List<User>> allUser();
}

record Message(String toUserId, String fromUserId, String text, Instant timestamp) {
	public Message(String toUserId, String fromUserId, String text) {
		this(toUserId, fromUserId, text, Instant.now());
	}
}

@Configuration
@RequiredArgsConstructor
@Slf4j
class WebSocketConfiguration {
	private final RabbitAdmin rabbitAdmin;
	private final Sender sender;
	private final Receiver receiver;
	private final JsonMapper jsonMapper;
	private final String exchangeNamePattern = "user.%s.inbox";
	private final String queueNamePattern = "ws.inbox.%s.%s";

	@Bean
	public HandlerMapping handlerMapping() {
		Map<String, WebSocketHandler> map = new HashMap<>();
		map.put("/ws/chat", session -> {

			var userId = session.getHandshakeInfo().getUri().getQuery().split("=")[1];
			log.info("UserId: {}", userId);

			// Unique queue per WebSocket session (supports multiple devices)
			String queueName = queueNamePattern.formatted(userId ,UUID.randomUUID().toString());

			//create queue
			//TODO - consider using non-exclusive queues and have a separate cleanup mechanism to support multiple WebSocket sessions per user (e.g. multiple devices)
			var queue = QueueBuilder.nonDurable(queueName).autoDelete().build();
			rabbitAdmin.declareQueue(queue);

			//create exchange for user's inbox if not exists
			// (multiple WebSocket sessions for the same user will share the same inbox exchange, so we can use it as a fanout to broadcast messages to all sessions/devices of the same user)
			var exchangeName = exchangeNamePattern.formatted(userId);
			var exchange = ExchangeBuilder
					.directExchange(exchangeName)
					.durable(true)   // <-- durable! This inbox persists across restarts
					.build();
			rabbitAdmin.declareExchange(exchange);

			// Bind queue to user's inbox exchange
			//rabbitAdmin.declareBinding(new Binding(queueName, Binding.DestinationType.QUEUE, exchangeName, "", null));
			rabbitAdmin.declareBinding(BindingBuilder.bind(queue).to(new FanoutExchange(exchangeName)));

			//---publishToRespectiveUserInbox---//
			// Incoming WebSocket message to be sent to RabbitMQ exchange
			// Each incoming WebSocket message is published to the user's inbox exchange, so it can be consumed by other WebSocket sessions (e.g. other devices) or any other consumers interested in messages for that user.
			var props = new AMQP.BasicProperties.Builder()
					.contentType("application/json")
					.deliveryMode(2)  // persistent — DMs should survive broker restart
					.build();
			Mono<Void> input = session
					.receive()
					.map(WebSocketMessage::getPayloadAsText)
					.map(textMessage -> jsonMapper.readValue(textMessage, Message.class))
					//save it in the db
					.map(message -> new Message(message.toUserId(), message.fromUserId(), message.text()))
					.flatMap(message -> sender.send(Mono.fromCallable(() -> new OutboundMessage(exchangeNamePattern.formatted(message.toUserId()), "", props, jsonMapper.writeValueAsBytes(message)))))
					.doOnError(throwable -> log.error("Error while ---publishToRespectiveUserInbox---", throwable))
					.then();



			//--- subscribeToInbox ---//
			// Websocket session has to subscribe to their respective RabbitMQ queue to receive messages published to the respective exchange.
			Flux<WebSocketMessage> messageFromQueue = receiver.consumeAutoAck(queueName)
					.mapNotNull(Delivery::getBody)
					.map(body -> new String(body, StandardCharsets.UTF_8))
					.filter(StringUtils::hasText)
					.doOnError(error -> log.error("Error while --- subscribeToInbox ---", error))
					.doFinally(signal -> {
						log.info("Cleaning up queue {} (signal: {})", queueName, signal);
						rabbitAdmin.deleteQueue(queueName);
					})
					.map(session::textMessage);

			return session.send(messageFromQueue)
					.doOnError(error -> log.error("WebSocket message failed to send after consuming message from queue {}", queueName, error))
					.and(input);

			/*
			return session
					.receive()
					.map(WebSocketMessage::getPayloadAsText)
					.map(session::textMessage)
					.as(session::send)
					.then();
			 */
		});
		int order = -1; // before annotated controllers

		return new SimpleUrlHandlerMapping(map, order);
	}
}