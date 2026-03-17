package com.example.webflux_rabbitmq;

import com.rabbitmq.client.ConnectionFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
		map.put("/ws/chat-room", session -> {

			
			return session.receive().then();
		});
		int order = -1; // before annotated controllers

		return new SimpleUrlHandlerMapping(map, order);
	}
}