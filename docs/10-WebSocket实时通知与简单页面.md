# 第10阶段：WebSocket实时通知与简单页面

## 1. 阶段目标

本阶段让服务端在RabbitMQ消费者完成订单后主动通知用户，并提供一个简单页面展示完整抢票流程。

实现顺序：

```text
1. 建立WebSocket连接
2. 管理用户与连接的对应关系
3. 消费成功后推送SUCCESS
4. 消费失败后推送FAILED
5. 断线时通过状态查询兜底
6. 创建活动列表页面
7. 创建抢票按钮和结果展示
```

## 2. 为什么使用WebSocket

HTTP通常是客户端主动请求：

```text
客户端请求 → 服务端响应
```

异步订单完成时间不确定。如果客户端每隔一秒查询一次，会产生大量无效请求。

WebSocket建立长连接后：

```text
RabbitMQ消费者完成订单
  ↓
服务端主动发送消息
  ↓
页面立即展示结果
```

## 3. 分层职责边界

| 层 | 职责 |
| --- | --- |
| HTTP接口层 | 活动查询、提交抢票、请求状态查询 |
| WebSocket接口层 | 建立连接、断开连接、发送消息，不创建订单 |
| 业务层 | 决定何时通知、通知内容和失败兜底 |
| 数据访问层 | 复用已有Mapper，并且只允许业务Service间接调用；WebSocket组件不得直接调用Mapper |
| 消息消费者 | 完成订单后调用通知Service，不直接管理Session |
| 配置层 | 注册WebSocket路径、握手拦截器和跨域规则 |
| 前端页面 | 调用HTTP接口、维护WebSocket、展示状态 |

调用关系：

```text
RabbitMQ Consumer
  ↓
AsyncOrderService完成事务
  ↓
OrderNotificationService
  ↓
WebSocketSessionRegistry
  ↓
用户浏览器
```

## 4. 增加依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>
```

初学版本使用Spring原生WebSocket Handler，不先引入STOMP和消息代理，减少概念数量。

## 5. 通知消息结构

```java
public record OrderResultMessage(
        String requestId,
        String status,
        String orderNo,
        String message
) {
}
```

JSON示例：

```json
{
  "requestId": "REQ-001",
  "status": "SUCCESS",
  "orderNo": "ORDER-001",
  "message": "抢票成功"
}
```

不要直接发送 `TicketOrder` Entity。

## 6. 配置层：注册WebSocket路径

```java
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(orderWebSocketHandler, "/ws/orders")
                .addInterceptors(userHandshakeInterceptor)
                .setAllowedOriginPatterns("http://localhost:*");
    }
}
```

生产环境不能使用无限制跨域。

## 7. 接口1：建立WebSocket连接

连接地址：

```text
ws://localhost:8080/ws/orders?userId=1
```

### 握手拦截器职责

```text
1. 从认证信息取得当前用户
2. 将userId写入WebSocketSession attributes
3. 验证用户是否合法
4. 拒绝缺少身份的连接
```

学习初期可暂时从Query String读取 `userId`，但必须明确这不安全：任何人都可以伪造其他用户ID。加入登录后应从Token或Session取得用户身份。

### Handler职责

```text
连接建立：注册Session
连接关闭：移除Session
传输错误：关闭并移除Session
收到客户端消息：只做心跳或忽略，不创建订单
```

### 验收

- 浏览器能够建立连接。
- 服务端记录用户连接。
- 关闭页面后连接被移除。
- 同一用户重复连接时策略明确：支持多终端或替换旧连接。

## 8. 连接注册表

`WebSocketSessionRegistry`维护：

```text
userId → 一个或多个WebSocketSession
```

使用线程安全集合，例如：

```java
ConcurrentHashMap<Long, Set<WebSocketSession>>
```

注册表只负责连接管理，不查询订单和数据库。

发送消息前检查：

```text
Session是否仍然open
发送是否异常
异常连接是否及时移除
```

## 9. 接口2：订单成功通知

RabbitMQ消费者成功完成MySQL事务后：

```text
1. Redis请求状态更新为SUCCESS
2. 事务已经提交
3. 调用OrderNotificationService.notifySuccess()
4. 序列化OrderResultMessage
5. 推送给对应用户的所有有效Session
```

通知必须在数据库事务成功后发送。否则用户可能收到成功消息，但事务最终回滚。

### 验收

- 用户提交抢票后先看到“排队中”。
- 消费者创建订单后页面收到SUCCESS。
- 消息包含requestId和orderNo。

## 10. 接口3：订单失败通知

消费者确认失败并完成补偿后：

```text
1. Redis请求状态更新为FAILED
2. 完成必要库存和资格补偿
3. 推送FAILED及可展示原因
```

不要把数据库堆栈、SQL或内部异常类名发给前端。

### 验收

- 售罄、重复购买等错误可以展示。
- 系统异常只显示通用提示。
- 失败通知与状态查询结果一致。

## 11. 接口4：断线查询兜底

WebSocket不是可靠消息存储。用户断线期间可能错过通知，因此页面必须保留：

```http
GET /api/seckill/requests/{requestId}
```

前端策略：

```text
提交后保存requestId
→ 等待WebSocket通知
→ 超过指定时间仍无消息
→ 调用状态查询接口
→ 页面刷新后也可继续查询
```

WebSocket负责实时体验，状态存储负责可靠查询。

## 12. 页面1：活动列表

页面使用已有接口：

```http
GET /api/events?page=1&size=20&status=ON_SALE
```

页面只展示：

```text
活动名称
地点
活动时间
票档和展示库存
抢票按钮
```

可以放在：

```text
src/main/resources/static/index.html
src/main/resources/static/js/app.js
src/main/resources/static/css/app.css
```

不需要引入复杂前端工程。

### 验收

- 页面能加载活动列表。
- 无活动时有空状态。
- 请求错误时有错误提示。

## 13. 页面2：提交抢票

点击按钮调用：

```http
POST /api/seckill/orders
```

响应QUEUED后：

```text
保存requestId
禁用当前票档按钮
显示“排队处理中”
等待WebSocket或状态查询结果
```

不要在客户端收到QUEUED时显示“抢票成功”。

## 14. 页面3：展示结果

WebSocket收到：

```text
SUCCESS → 显示订单号和查询订单按钮
FAILED → 显示失败原因并恢复按钮状态
```

页面需要根据 `requestId` 匹配当前请求，避免把其他请求结果展示到错误位置。

## 15. 心跳与重连

简单实现：

```text
连接关闭后延迟重连
限制最大重连间隔
页面隐藏时减少无意义重连
重新连接后查询未完成requestId状态
```

不要创建无限快速重连循环。

## 16. 测试顺序

```text
1. 单用户建立和关闭连接
2. 同用户多页面连接
3. 订单成功通知
4. 订单失败通知
5. 用户离线时订单完成
6. 用户重连后通过HTTP查询结果
7. 页面刷新后恢复未完成请求
8. 多用户通知不会串号
```

## 17. 阶段验收清单

- [ ] WebSocket握手身份明确。
- [ ] Session注册表线程安全。
- [ ] 成功通知在事务提交后发送。
- [ ] 失败通知在状态和补偿完成后发送。
- [ ] 断线时可通过HTTP查询结果。
- [ ] 页面不会把QUEUED显示为成功。
- [ ] 多用户消息不会互相发送。
- [ ] 页面只实现核心流程，没有过度投入UI。

## 18. 下一阶段

下一阶段启动多个应用实例并引入Redis Cluster。由于WebSocket连接只存在于某一个实例，需要通过Redis Pub/Sub等方式把通知路由到拥有用户连接的实例。

## 19. 官方参考

- Spring WebSocket：`https://docs.spring.io/spring-framework/reference/web/websocket.html`
- Spring Boot WebSocket：`https://docs.spring.io/spring-boot/3.5/reference/messaging/websockets.html`

## 20. 完整代码答案

本阶段只增加“通知通道”，不把订单逻辑搬进WebSocket。HTTP负责提交和查询，RabbitMQ消费者负责改变状态，WebSocket只把结果推给浏览器；断线时页面仍通过HTTP查询兜底。

### 20.1 Maven依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>
```

### 20.2 连接注册表

`src/main/java/com/example/flashticket/websocket/WebSocketSessionRegistry.java`

```java
package com.example.flashticket.websocket;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
@Component
public class WebSocketSessionRegistry {
    private final Map<Long,Set<WebSocketSession>> sessions=new ConcurrentHashMap<>();
    public void add(Long userId,WebSocketSession session){sessions.computeIfAbsent(userId,k->ConcurrentHashMap.newKeySet()).add(session);}
    public void remove(Long userId,WebSocketSession session){
        Set<WebSocketSession> values=sessions.get(userId);
        if(values==null)return; values.remove(session); if(values.isEmpty())sessions.remove(userId);
    }
    public Set<WebSocketSession> get(Long userId){return sessions.getOrDefault(userId,Set.of());}
}
```

### 20.3 握手与Handler

`src/main/java/com/example/flashticket/websocket/UserHandshakeInterceptor.java`

```java
package com.example.flashticket.websocket;
import java.util.Map;
import org.springframework.http.server.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.server.HandshakeInterceptor;
public class UserHandshakeInterceptor implements HandshakeInterceptor {
    public boolean beforeHandshake(ServerHttpRequest req,ServerHttpResponse res,WebSocketHandler h,Map<String,Object> attrs){
        String query=req.getURI().getQuery();
        if(query==null)return false;
        for(String part:query.split("&")){
            String[] pair=part.split("=",2);
            if(pair.length==2&&pair[0].equals("userId")){
                try{attrs.put("userId",Long.valueOf(pair[1]));return true;}catch(NumberFormatException ignored){return false;}
            }
        }
        return false;
    }
    public void afterHandshake(ServerHttpRequest r,ServerHttpResponse s,WebSocketHandler h,Exception e){}
}
```

`src/main/java/com/example/flashticket/websocket/OrderWebSocketHandler.java`

```java
package com.example.flashticket.websocket;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
@Component
public class OrderWebSocketHandler extends TextWebSocketHandler {
    private final WebSocketSessionRegistry registry;
    public OrderWebSocketHandler(WebSocketSessionRegistry registry){this.registry=registry;}
    public void afterConnectionEstablished(WebSocketSession s){registry.add(userId(s),s);}
    public void afterConnectionClosed(WebSocketSession s,CloseStatus status){registry.remove(userId(s),s);}
    public void handleTransportError(WebSocketSession s,Throwable e) throws Exception {registry.remove(userId(s),s);s.close();}
    private Long userId(WebSocketSession s){return (Long)s.getAttributes().get("userId");}
}
```

`src/main/java/com/example/flashticket/config/WebSocketConfig.java`

```java
package com.example.flashticket.config;
import com.example.flashticket.websocket.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;
@Configuration @EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final OrderWebSocketHandler handler;
    public WebSocketConfig(OrderWebSocketHandler handler){this.handler=handler;}
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){
        registry.addHandler(handler,"/ws/orders").addInterceptors(new UserHandshakeInterceptor()).setAllowedOriginPatterns("*");
    }
}
```

### 20.4 通知业务层

`src/main/java/com/example/flashticket/websocket/OrderNotification.java`

```java
package com.example.flashticket.websocket;
public record OrderNotification(String requestId,String status,Long orderId,String message) {}
```

`src/main/java/com/example/flashticket/service/OrderNotificationService.java`

```java
package com.example.flashticket.service;
import com.example.flashticket.websocket.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
@Service
public class OrderNotificationService {
    private static final Logger log=LoggerFactory.getLogger(OrderNotificationService.class);
    private final WebSocketSessionRegistry registry; private final ObjectMapper json;
    public OrderNotificationService(WebSocketSessionRegistry registry,ObjectMapper json){this.registry=registry;this.json=json;}
    public void success(Long userId,String requestId,Long orderId){send(userId,new OrderNotification(requestId,"SUCCESS",orderId,"抢票成功"));}
    public void failed(Long userId,String requestId,String reason){send(userId,new OrderNotification(requestId,"FAILED",null,reason));}
    public void send(Long userId,OrderNotification value){
        try {
            TextMessage message=new TextMessage(json.writeValueAsString(value));
            for(var session:registry.get(userId)) if(session.isOpen()) synchronized(session){session.sendMessage(message);}
        } catch(IOException e){log.warn("WebSocket通知失败，客户端将通过HTTP查询兜底",e);}
    }
}
```

### 20.5 完整替换消费者

`src/main/java/com/example/flashticket/messaging/SeckillConsumer.java`

```java
package com.example.flashticket.messaging;
import com.example.flashticket.config.RabbitConfig;
import com.example.flashticket.dto.OrderDtos.CreateOrderRequest;
import com.example.flashticket.mapper.SeckillRequestMapper;
import com.example.flashticket.service.*;
import com.example.flashticket.vo.OrderVos.OrderVo;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
@Component
public class SeckillConsumer {
    private final SeckillRequestMapper requests; private final TicketOrderService orders;
    private final RedisReservationService reservation; private final OrderNotificationService notifications;
    public SeckillConsumer(SeckillRequestMapper requests,TicketOrderService orders,
            RedisReservationService reservation,OrderNotificationService notifications){
        this.requests=requests;this.orders=orders;this.reservation=reservation;this.notifications=notifications;
    }
    @RabbitListener(queues=RabbitConfig.QUEUE)
    public void consume(SeckillMessage m){
        if(requests.claim(m.requestId())!=1)return;
        try{
            OrderVo order=orders.create(new CreateOrderRequest(m.userId(),m.stockId()));
            requests.success(m.requestId(),order.id());
            notifications.success(m.userId(),m.requestId(),order.id());
        }catch(RuntimeException e){
            reservation.rollback(m.stockId(),m.userId());
            String reason=e.getMessage()==null?"订单创建失败":e.getMessage();
            reason=reason.substring(0,Math.min(reason.length(),255));
            requests.fail(m.requestId(),reason); notifications.failed(m.userId(),m.requestId(),reason);
        }
    }
}
```

### 20.6 完整简单页面

`src/main/resources/static/index.html`

```html
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
  <title>FlashTicket</title>
  <style>
    body{font-family:Arial,sans-serif;max-width:900px;margin:32px auto;padding:0 16px;color:#202124}
    input,button{padding:9px;margin:4px}button{cursor:pointer}.row{border-bottom:1px solid #ddd;padding:12px 0}
    #result{white-space:pre-wrap;background:#f5f7f9;padding:12px;min-height:60px}
  </style>
</head>
<body>
  <h1>FlashTicket 抢票</h1>
  <label>用户ID <input id="userId" type="number" value="1"></label>
  <button onclick="connect()">连接通知</button><button onclick="loadEvents()">刷新活动</button>
  <p id="connection">未连接</p><div id="events"></div><h2>处理结果</h2><pre id="result"></pre>
  <script>
    let socket,currentRequestId,pollTimer;
    const result=document.querySelector('#result');
    function show(v){result.textContent=typeof v==='string'?v:JSON.stringify(v,null,2)}
    function connect(){
      const userId=document.querySelector('#userId').value;
      if(socket)socket.close();
      const protocol=location.protocol==='https:'?'wss':'ws';
      socket=new WebSocket(`${protocol}://${location.host}/ws/orders?userId=${userId}`);
      socket.onopen=()=>document.querySelector('#connection').textContent='通知已连接';
      socket.onclose=()=>document.querySelector('#connection').textContent='通知已断开，将使用HTTP查询';
      socket.onmessage=e=>{const value=JSON.parse(e.data);show(value);if(value.requestId===currentRequestId)clearInterval(pollTimer)};
    }
    async function loadEvents(){
      const body=await fetch('/api/events?page=1&size=100').then(r=>r.json());
      const box=document.querySelector('#events');box.innerHTML='';
      for(const event of body.data.records){
        const div=document.createElement('div');div.className='row';div.textContent=`${event.name} @ ${event.venue} `;
        const button=document.createElement('button');button.textContent='查看票档';button.onclick=()=>loadStocks(event.id,div);
        div.append(button);box.append(div);
      }
    }
    async function loadStocks(eventId,box){
      const body=await fetch(`/api/events/${eventId}/stocks`).then(r=>r.json());
      for(const stock of body.data){
        const button=document.createElement('button');button.textContent=`抢 ${stock.tierName} ¥${stock.price}`;
        button.onclick=()=>submit(stock.id);box.append(button);
      }
    }
    async function submit(stockId){
      const userId=Number(document.querySelector('#userId').value);
      const body=await fetch('/api/seckill/requests',{method:'POST',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({userId,stockId})}).then(r=>r.json());
      if(body.code!==0){show(body);return} currentRequestId=body.data.requestId;show(body.data);
      clearInterval(pollTimer);pollTimer=setInterval(()=>poll(currentRequestId),1500);
    }
    async function poll(id){
      const body=await fetch(`/api/seckill/requests/${id}`).then(r=>r.json());show(body.data);
      if(body.data.status===1||body.data.status===2)clearInterval(pollTimer);
    }
    connect();loadEvents();
  </script>
</body>
</html>
```

### 20.7 验证顺序

先验证连接建立和断开，再验证成功通知、失败通知，最后关闭WebSocket后提交请求，确认页面依靠HTTP轮询仍能得到最终结果。页面只是学习用，不包含登录鉴权；实际项目不能直接信任查询参数中的userId。

