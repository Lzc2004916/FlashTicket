# 第11阶段：多实例部署与Redis Cluster

## 1. 阶段目标

本阶段把单实例应用升级为至少两个Spring Boot实例，并将Redis切换到Cluster模式。

实现顺序：

```text
1. 启动两个应用实例
2. 增加健康检查
3. 配置Nginx负载均衡
4. 配置WebSocket代理
5. 搭建Redis Cluster
6. 修改Spring和Redisson集群配置
7. 验证Lua Key同槽位
8. 跨实例转发WebSocket通知
9. 验证实例和Redis节点故障
```

## 2. 目标架构

```text
                 ┌─ FlashTicket :8080 ─┐
客户端 → Nginx ──┤                     ├─ MySQL
                 └─ FlashTicket :8081 ─┘
                           │
                    Redis Cluster
                           │
                       RabbitMQ
```

所有应用实例：

- 连接同一个MySQL。
- 连接同一个Redis Cluster。
- 消费同一个RabbitMQ队列。
- 本地内存和WebSocket Session互不共享。

## 3. 分层职责边界

| 层 | 职责 |
| --- | --- |
| 接口层 | 业务接口保持不变，不感知请求落在哪个实例 |
| 业务层 | 不保存只在单实例有效的业务状态 |
| 数据访问层 | 继续访问共享MySQL |
| 配置层 | 多实例端口、Redis Cluster、Redisson、Nginx、WebSocket代理 |
| 基础设施层 | 负载均衡、故障转移、集群槽位和健康检查 |

业务代码不能通过静态变量保存库存、订单状态或抢票资格。

## 4. 启动两个应用实例

先完成最简单的多实例验证：

```powershell
java -jar target\flash-ticket-0.0.1-SNAPSHOT.jar --server.port=8080
java -jar target\flash-ticket-0.0.1-SNAPSHOT.jar --server.port=8081
```

两个实例必须成功启动，并连接相同的外部服务。

### 验收

- 8080和8081同时监听。
- 两个实例查询到相同活动和订单。
- 在8080创建的数据可以从8081查询。

## 5. 接口1：健康检查

加入Actuator依赖：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

当前阶段只暴露：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info
```

验证：

```http
GET /actuator/health
```

健康检查是基础设施接口，不放在业务Controller中。

## 6. Nginx负载均衡

示例：

```nginx
upstream flash_ticket_backend {
    server 127.0.0.1:8080;
    server 127.0.0.1:8081;
}

server {
    listen 80;

    location / {
        proxy_pass http://flash_ticket_backend;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

应用配置转发头策略，确保日志和链接能够识别原始请求信息。

### 验收

- 通过Nginx访问活动接口。
- 请求能够分发到两个实例。
- 停止一个实例后另一个仍能响应。

## 7. WebSocket代理

```nginx
location /ws/ {
    proxy_pass http://flash_ticket_backend;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_read_timeout 60m;
}
```

WebSocket建立后会固定在某个实例，但RabbitMQ消费者可能运行在另一个实例，因此不能只依赖本地Session注册表直接通知。

## 8. Redis Cluster拓扑

学习环境建议：

```text
7001 主节点
7002 主节点
7003 主节点
7004 从节点
7005 从节点
7006 从节点
```

Redis Cluster把Key分布到16384个Hash Slot。三主三从可以练习数据分片和主从故障转移。

创建集群前确保：

- 节点使用不同端口和数据目录。
- 开启 `cluster-enabled yes`。
- 节点之间总线端口可通信。
- 每个节点目录独立。

集群创建命令示例：

```powershell
redis-cli --cluster create `
  127.0.0.1:7001 127.0.0.1:7002 127.0.0.1:7003 `
  127.0.0.1:7004 127.0.0.1:7005 127.0.0.1:7006 `
  --cluster-replicas 1
```

## 9. Spring Redis Cluster配置

```yaml
spring:
  data:
    redis:
      cluster:
        nodes:
          - 127.0.0.1:7001
          - 127.0.0.1:7002
          - 127.0.0.1:7003
          - 127.0.0.1:7004
          - 127.0.0.1:7005
          - 127.0.0.1:7006
        max-redirects: 3
      timeout: 2s
```

不要再同时保留单节点 `host` 和 `port` 配置。

Redisson也必须切换为Cluster Servers配置，不能继续连接单个节点。

## 10. 验证接口2：缓存查询

复用：

```http
GET /api/events/{id}
GET /api/events/{eventId}/stocks
```

验证：

- Key能够写入不同槽位。
- 应用能够自动处理MOVED重定向。
- 关闭一个从节点不影响查询。
- 关闭一个主节点后等待故障转移并再次查询。

## 11. 验证接口3：Lua抢票

复用：

```http
POST /api/seckill/orders
```

Lua涉及的Key必须同槽：

```text
flash:ticket:seckill:{10}:stock
flash:ticket:seckill:{10}:users
```

检查槽位：

```powershell
redis-cli -c -p 7001 CLUSTER KEYSLOT "flash:ticket:seckill:{10}:stock"
redis-cli -c -p 7001 CLUSTER KEYSLOT "flash:ticket:seckill:{10}:users"
```

两个结果必须相同，否则Lua会报 `CROSSSLOT`。

## 12. 跨实例WebSocket通知

问题场景：

```text
用户WebSocket连接在实例A
RabbitMQ消息由实例B消费
实例B本地找不到用户Session
```

解决方案：Redis Pub/Sub广播订单结果。

流程：

```text
消费者完成订单
  ↓
发布OrderResultMessage到Redis Channel
  ↓
所有实例订阅该Channel
  ↓
每个实例检查本地是否有该用户Session
  ↓
拥有连接的实例发送WebSocket消息
```

Channel示例：

```text
flash:ticket:channel:order-result
```

Pub/Sub只负责实时通知，不负责可靠存储。可靠结果仍在请求状态Key和MySQL中。

## 13. 接口4：跨实例通知验证

验证顺序：

```text
1. 页面WebSocket连接到实例A
2. 抢票HTTP请求通过Nginx进入任意实例
3. RabbitMQ消息由实例B消费
4. 实例B发布Redis通知
5. 实例A收到通知并推送页面
6. 页面显示正确requestId结果
```

## 14. 故障测试

### 应用实例故障

停止8080，确认新HTTP请求由8081处理。已经连接到8080的WebSocket会断开，前端必须重连。

### Redis从节点故障

关闭一个从节点，业务应继续运行，但冗余能力下降。

### Redis主节点故障

关闭一个主节点，观察从节点晋升。故障转移期间允许短暂错误，但应用应在集群恢复后继续工作。

### RabbitMQ或MySQL故障

明确记录请求状态、消息重试和失败行为，不要把基础设施故障伪装成抢票成功。

## 15. 阶段验收清单

- [ ] 两个Spring Boot实例同时运行。
- [ ] Nginx能够负载均衡。
- [ ] `/actuator/health`可供健康检查。
- [ ] Redis Cluster三主三从建立成功。
- [ ] Spring Redis与Redisson均使用Cluster配置。
- [ ] Lua Key槽位相同。
- [ ] 单个应用实例停止后HTTP仍可用。
- [ ] WebSocket跨实例通知正常。
- [ ] Pub/Sub之外仍有可靠状态查询兜底。

## 16. 下一阶段

最后阶段使用JMeter、Actuator、Micrometer、GC日志和JFR建立基线，定位瓶颈，再进行可验证的调优。

## 17. 官方参考

- Redis Cluster：`https://redis.io/docs/latest/operate/oss_and_stack/management/scaling/`
- Redis Cluster Specification：`https://redis.io/docs/latest/operate/oss_and_stack/reference/cluster-spec/`
- Spring Boot Actuator：`https://docs.spring.io/spring-boot/3.5/reference/actuator/`

## 18. 完整代码答案

本阶段不新增订单规则，只解决部署拓扑。HTTP由Nginx负载均衡；Redis Cluster保存缓存、Lua资格和锁；订单通知先发布到Redis频道，每个实例收到后只向本机持有的WebSocket连接发送。

### 18.1 开发用Redis Cluster

`docker-compose.cluster.yml`

```yaml
services:
  redis-cluster:
    image: grokzen/redis-cluster:7.0.10
    environment:
      IP: 0.0.0.0
      INITIAL_PORT: 7000
      MASTERS: 3
      SLAVES_PER_MASTER: 1
    ports:
      - "7000-7005:7000-7005"
```

这是单机学习拓扑，不代表生产部署。启动后执行 `docker compose -f docker-compose.cluster.yml up -d`，再用 `redis-cli -c -p 7000 cluster nodes` 检查6个节点。

### 18.2 完整集群配置

`src/main/resources/application-cluster.yml`

```yaml
spring:
  config:
    activate:
      on-profile: cluster
  data:
    redis:
      cluster:
        nodes: localhost:7000,localhost:7001,localhost:7002,localhost:7003,localhost:7004,localhost:7005
        max-redirects: 5
      password: ${REDIS_PASSWORD:}
      timeout: 2s
app:
  instance-id: ${INSTANCE_ID:${server.port}}
```

完整替换 `src/main/java/com/example/flashticket/config/RedissonConfig.java`：

```java
package com.example.flashticket.config;
import java.util.Arrays;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
@Configuration
public class RedissonConfig {
    @Bean(destroyMethod="shutdown")
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:localhost}")String host,
            @Value("${spring.data.redis.port:6379}")int port,
            @Value("${spring.data.redis.cluster.nodes:}")String nodes,
            @Value("${spring.data.redis.password:}")String password){
        Config config=new Config();
        if(nodes!=null&&!nodes.isBlank()){
            String[] addresses=Arrays.stream(nodes.split(",")).map(String::trim)
                    .map(v->"redis://"+v).toArray(String[]::new);
            var cluster=config.useClusterServers().addNodeAddress(addresses);
            if(password!=null&&!password.isBlank())cluster.setPassword(password);
        }else{
            var single=config.useSingleServer().setAddress("redis://"+host+":"+port);
            if(password!=null&&!password.isBlank())single.setPassword(password);
        }
        return Redisson.create(config);
    }
}
```

### 18.3 实例识别接口

`src/main/java/com/example/flashticket/controller/InstanceController.java`

```java
package com.example.flashticket.controller;
import com.example.flashticket.common.ApiResponse;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
@RestController
public class InstanceController {
    private final String instanceId;
    public InstanceController(@Value("${app.instance-id:${server.port}}")String instanceId){this.instanceId=instanceId;}
    @GetMapping("/api/instance")
    public ApiResponse<Map<String,String>> instance(){return ApiResponse.success(Map.of("instanceId",instanceId));}
}
```

### 18.4 Nginx完整配置

`deploy/nginx.conf`

```nginx
events { worker_connections 1024; }
http {
    upstream flash_ticket {
        least_conn;
        server 127.0.0.1:8080;
        server 127.0.0.1:8081;
    }
    server {
        listen 80;
        location / {
            proxy_pass http://flash_ticket;
            proxy_set_header Host $host;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        }
        location /ws/ {
            proxy_pass http://flash_ticket;
            proxy_http_version 1.1;
            proxy_set_header Upgrade $http_upgrade;
            proxy_set_header Connection "upgrade";
            proxy_read_timeout 3600s;
        }
    }
}
```

### 18.5 跨实例通知：本地发送器

`src/main/java/com/example/flashticket/service/LocalNotificationSender.java`

```java
package com.example.flashticket.service;
import com.example.flashticket.websocket.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
@Service
public class LocalNotificationSender {
    private final WebSocketSessionRegistry registry; private final ObjectMapper json;
    public LocalNotificationSender(WebSocketSessionRegistry registry,ObjectMapper json){this.registry=registry;this.json=json;}
    public void send(Long userId,OrderNotification value){
        try{
            TextMessage message=new TextMessage(json.writeValueAsString(value));
            for(var s:registry.get(userId))if(s.isOpen())synchronized(s){s.sendMessage(message);}
        }catch(Exception e){throw new IllegalStateException("本地WebSocket发送失败",e);}
    }
}
```

### 18.6 跨实例通知：发布和订阅

完整替换 `src/main/java/com/example/flashticket/service/OrderNotificationService.java`：

```java
package com.example.flashticket.service;
import com.example.flashticket.websocket.OrderNotification;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
@Service
public class OrderNotificationService {
    private static final Logger log=LoggerFactory.getLogger(OrderNotificationService.class);
    public static final String CHANNEL="ft:order:notifications";
    private final StringRedisTemplate redis; private final ObjectMapper json;
    public OrderNotificationService(StringRedisTemplate redis,ObjectMapper json){this.redis=redis;this.json=json;}
    public void success(Long userId,String requestId,Long orderId){publish(userId,new OrderNotification(requestId,"SUCCESS",orderId,"抢票成功"));}
    public void failed(Long userId,String requestId,String reason){publish(userId,new OrderNotification(requestId,"FAILED",null,reason));}
    private void publish(Long userId,OrderNotification value){
        try{redis.convertAndSend(CHANNEL,json.writeValueAsString(new Envelope(userId,value)));}
        catch(Exception e){log.warn("实时通知发布失败，客户端将通过HTTP查询兜底",e);}
    }
    public record Envelope(Long userId,OrderNotification notification){}
}
```

`src/main/java/com/example/flashticket/service/RedisNotificationSubscriber.java`

```java
package com.example.flashticket.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
@Component
public class RedisNotificationSubscriber implements MessageListener {
    private final ObjectMapper json; private final LocalNotificationSender local;
    public RedisNotificationSubscriber(ObjectMapper json,LocalNotificationSender local){this.json=json;this.local=local;}
    public void onMessage(Message message,byte[] pattern){
        try{
            var value=json.readValue(message.getBody(),OrderNotificationService.Envelope.class);
            local.send(value.userId(),value.notification());
        }catch(Exception e){throw new IllegalStateException("通知订阅处理失败",e);}
    }
}
```

`src/main/java/com/example/flashticket/config/RedisPubSubConfig.java`

```java
package com.example.flashticket.config;
import com.example.flashticket.service.*;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.*;
@Configuration
public class RedisPubSubConfig {
    @Bean
    RedisMessageListenerContainer listenerContainer(RedisConnectionFactory factory,RedisNotificationSubscriber subscriber){
        RedisMessageListenerContainer c=new RedisMessageListenerContainer();c.setConnectionFactory(factory);
        c.addMessageListener(subscriber,new ChannelTopic(OrderNotificationService.CHANNEL));
        return c;
    }
}
```

### 18.7 启动与验证

```powershell
$env:SPRING_PROFILES_ACTIVE='cluster'; $env:SERVER_PORT='8080'; $env:INSTANCE_ID='node-8080'; .\mvnw.cmd spring-boot:run
$env:SPRING_PROFILES_ACTIVE='cluster'; $env:SERVER_PORT='8081'; $env:INSTANCE_ID='node-8081'; .\mvnw.cmd spring-boot:run
```

两个命令要在两个终端运行。通过Nginx重复访问 `/api/instance` 应看到两个实例；浏览器连接其中一个实例，而RabbitMQ消息由另一个实例消费时，仍应收到通知。Redis Pub/Sub不保存历史消息，所以HTTP状态查询仍是可靠兜底。

