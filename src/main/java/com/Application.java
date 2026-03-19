package com;

import com.chat.service.UserHttpClient;
import com.rabbitmq.client.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import reactor.rabbitmq.*;

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
	UserHttpClient userHttpClient(WebClient.Builder webClientBuilder) {
		WebClientAdapter adapter = WebClientAdapter.create(webClientBuilder.build());
		HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter).build();
		return factory.createClient(UserHttpClient.class);
	}
}

