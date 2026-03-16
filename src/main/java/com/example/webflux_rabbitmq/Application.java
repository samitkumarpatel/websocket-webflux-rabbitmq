package com.example.webflux_rabbitmq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Delivery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

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
	RouterFunction<ServerResponse> routerFunction(RouterHandler routerHandler) {
		return RouterFunctions
				.route()
				.POST("/room", routerHandler::createNewRoom)
				.build();
	}
}

@Document
record Room(@Id String id, String title, Instant createdAt) {
	public Room(String id, String title) {
        this(id, title, Instant.now());
    }

	public Room withId(String id) {
		return new Room(id, this.title, Instant.now());
	}
}

interface RoomRepository extends ReactiveMongoRepository<Room, String> {}

@Component
@RequiredArgsConstructor
class RouterHandler {
	private final RabbitAdmin rabbitAdmin;
	private final RoomRepository roomRepository;

	public Mono<ServerResponse> createNewRoom(ServerRequest request) {
		return request
				.bodyToMono(Room.class)
				.map(body -> new Room(null, body.title()))
				.flatMap(roomRepository::save)
				.doOnNext(dbRoom -> rabbitAdmin
						.declareExchange(
								ExchangeBuilder
										.fanoutExchange(formatExchange(dbRoom.id()))
										.durable(true)
										.build()
						)
				)
				.flatMap(ServerResponse.ok()::bodyValue);
	}

    private String formatExchange(String id) {
        return "room.%s.events".formatted(id);
    }
}

@Configuration
@RequiredArgsConstructor
@Slf4j
class WebSocketConfiguration {
	private final RabbitAdmin rabbitAdmin;
	private final Sender sender;
	private final Receiver receiver;

	@Bean
	public HandlerMapping handlerMapping() {
		Map<String, WebSocketHandler> map = new HashMap<>();
		map.put("/ws", session -> {
			//get the roomId from URI ws://localhost:8080/ws?roomId=abc1123A
			String roomId = session.getHandshakeInfo().getUri().getQuery().split("=")[1];
			log.info("RoomId : {}",roomId);

			var queueName = "ws.%s.%s".formatted(roomId, UUID.randomUUID().toString());
			var exchange = "room.%s.events".formatted(roomId);
			//create a queue and attached to the Exchange
			var queue = QueueBuilder.nonDurable(queueName).exclusive().autoDelete().build();
			rabbitAdmin.declareQueue(queue);
			rabbitAdmin.declareBinding(BindingBuilder.bind(queue).to(new FanoutExchange(exchange)));


			/*
			//send message to the queue if any
			var input = session.receive()
					.map(WebSocketMessage::getPayloadAsText);

			//after receive a message , put that in the exchange/queue

			//consume the queue
			Flux<String> source = input.map(message -> "["+Instant.now()+"]: "+ message);

			return session.send(source.map(session::textMessage));

			*/

			/*

			//add message to the queue
			Mono<Void> input = session.receive()
					.map(WebSocketMessage::getPayloadAsText)
					.doOnNext(message -> {
						log.info("session.receive() :: {}", message);
						var props = new AMQP.BasicProperties.Builder()
								.contentType(MediaType.TEXT_PLAIN_VALUE)
								.deliveryMode(1) // non-persistent for speed
								.build();
						var messageToQueue = new OutboundMessage(
								exchange,
								"",   // routing key ignored for fan-out
								props,
								message.getBytes()
						);
						sender.send(Mono.just(messageToQueue));
					})
					.then();

			//listen to the exchange
			Flux<String> source = receiver.consumeAutoAck(queueName)
					.mapNotNull(Delivery::getBody)
					.map(String::new)
					.filter(StringUtils::hasText)
					.doOnCancel(() -> {
						// Clean up queue when WebSocket disconnects
						rabbitAdmin.deleteQueue(queueName);
						log.info("Deleted queue {}", queueName);
					});

			Mono<Void> output = session.send(source.map(session::textMessage));

			return input.and(output);

			 */
			var props = new AMQP.BasicProperties.Builder()
					.contentType(MediaType.TEXT_PLAIN_VALUE)
					.deliveryMode(1) // non-persistent for speed
					.build();

			Flux<OutboundMessage> outbound = session.receive()
					.map(WebSocketMessage::getPayloadAsText)
					.map(message -> new OutboundMessage(exchange, "", props, message.getBytes(StandardCharsets.UTF_8)));

			Flux<String> source = receiver.consumeAutoAck(queueName)
					.mapNotNull(Delivery::getBody)
					.map(body -> new String(body, StandardCharsets.UTF_8))
					.filter(StringUtils::hasText);

			Mono<Void> output = session.send(source.map(session::textMessage))
					.doOnError(error -> log.error("WebSocket send failed for queue {}", queueName, error));



			return sender.send(outbound).and(output).then();
		});
		int order = -1; // before annotated controllers

		return new SimpleUrlHandlerMapping(map, order);
	}
}