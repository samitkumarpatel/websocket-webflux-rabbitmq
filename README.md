## websocket-webflux-rabbitmq :: user-to-user


### How to run this application
```
$ ./mvnw spring-boot:test-run
```
### How to test this application
> Make sure you have `websocat` installed on your machine.
> 
```
$ websocat -E 'ws://localhost:8080/ws/chat?userId=1'
##Payload from command line would be
{"toUserId":"2","fromUserId":"1","text":"Hello User 2!"}
...you should see the message in the terminal where you ran the first command.

$ websocat -E 'ws://localhost:8080/ws/chat?userId=2'
##Payload from command line would be
{"toUserId":"1","fromUserId":"2","text":"Hello User 1!"}
...you should see the message in the terminal where you ran the first command.
```