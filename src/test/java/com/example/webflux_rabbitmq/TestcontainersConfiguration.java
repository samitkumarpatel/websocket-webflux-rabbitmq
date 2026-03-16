package com.example.webflux_rabbitmq;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	MongoDBContainer mongoDbContainer() {
		return new MongoDBContainer(DockerImageName.parse("mongo:latest"));
	}

	@Bean
	@ServiceConnection
	RabbitMQContainer rabbitContainer() {
		var rabbitMqContainer = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management"));
		rabbitMqContainer.withExposedPorts(5672, 15672);
		rabbitMqContainer.start();
		System.out.println("=================================================");
		System.out.println("RabbitMQ Management UI: http://localhost:" + rabbitMqContainer.getMappedPort(15672));
		System.out.println("=================================================");
		return rabbitMqContainer;
	}

}
