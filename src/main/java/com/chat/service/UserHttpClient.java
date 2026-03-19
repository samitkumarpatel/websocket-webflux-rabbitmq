package com.chat.service;

import com.chat.model.User;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import reactor.core.publisher.Mono;

import java.util.List;

@HttpExchange(url = "https://jsonplaceholder.typicode.com")
public interface UserHttpClient {

    @GetExchange("/Users")
    Mono<List<User>> getUsers();
}
