# 第2阶段：MySQL数据库设计与连接

## 1. 阶段目标

本阶段建立数据库结构并让 Spring Boot 成功连接 MySQL，但暂不实现业务CRUD接口。

完成后应具备：

- `flash_ticket` 数据库。
- 用户、活动、票档库存、订单四张基础表。
- MyBatis-Plus和MySQL驱动依赖。
- 本地数据库配置与默认配置分离。
- 数据源连接测试。
- 数据库字符集、索引和约束经过验证。

## 2. 数据库设计原则

先从业务对象出发，而不是先猜表数量：

```text
用户 app_user
活动 ticket_event
票档库存 ticket_stock
订单 ticket_order
```

对象关系：

```text
ticket_event 1 ───── N ticket_stock
app_user     1 ───── N ticket_order
ticket_stock 1 ───── N ticket_order
```

第一版采用逻辑外键，不创建数据库外键约束：

- 表之间仍然通过 `user_id`、`event_id`、`stock_id` 关联。
- 数据有效性由Service和测试保证。
- 避免后续高并发写入、数据迁移和删除时受到强外键限制。
- 唯一性和库存边界仍必须由数据库索引与条件更新兜底。

## 3. 分层职责边界

本阶段没有HTTP业务接口。

| 层 | 本阶段职责 |
| --- | --- |
| 接口层 | 保持为空，不为“测试连接”创建Controller |
| 业务层 | 保持为空，不编写用户或活动业务 |
| 数据访问层 | 只引入MyBatis-Plus基础环境，暂不创建业务Mapper |
| 配置层 | 配置DataSource、MyBatis-Plus基础参数和本地Profile |
| 数据库 | 建表、索引、默认值和唯一约束 |
| 测试层 | 通过DataSource执行连接验证，不依赖业务代码 |

验证链路：

```text
JUnit测试
   ↓
Spring Boot自动配置DataSource
   ↓
HikariCP连接池
   ↓
MySQL
   ↓
执行 SELECT 1
```

## 4. 增加Maven依赖

MyBatis-Plus Spring Boot 3 Starter：

```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    <version>3.5.18</version>
</dependency>
```

MySQL驱动：

```xml
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
```

不要再单独加入：

```text
mybatis-spring-boot-starter
mybatis
mybatis-spring
```

MyBatis-Plus Starter 已经管理这些依赖，重复引入容易产生版本冲突。

## 5. 创建数据库

文件建议放在：

```text
sql/01-schema.sql
```

```sql
CREATE DATABASE IF NOT EXISTS flash_ticket
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE flash_ticket;
```

使用 `utf8mb4`，避免中文和特殊字符存储问题。

## 6. 创建用户表

```sql
CREATE TABLE app_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '用户ID',
    username VARCHAR(50) NOT NULL COMMENT '用户名',
    phone VARCHAR(20) NOT NULL COMMENT '手机号',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '用户状态',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_app_user_phone (phone)
) ENGINE=InnoDB COMMENT='用户表';
```

关键规则：

- 手机号唯一，数据库负责最终兜底。
- 状态暂时使用字符串，后续Java中使用枚举。
- 时间字段由数据库提供默认值。

## 7. 创建活动表

```sql
CREATE TABLE ticket_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '活动ID',
    name VARCHAR(100) NOT NULL COMMENT '活动名称',
    description VARCHAR(500) NULL COMMENT '活动描述',
    venue VARCHAR(150) NOT NULL COMMENT '活动地点',
    event_time DATETIME NOT NULL COMMENT '活动时间',
    sale_start_time DATETIME NOT NULL COMMENT '开售时间',
    sale_end_time DATETIME NOT NULL COMMENT '停售时间',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT '活动状态',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_ticket_event_status_sale_time (status, sale_start_time),
    KEY idx_ticket_event_event_time (event_time)
) ENGINE=InnoDB COMMENT='票务活动表';
```

时间规则以后由Service校验：

```text
sale_start_time < sale_end_time <= event_time
```

## 8. 创建票档库存表

```sql
CREATE TABLE ticket_stock (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '票档ID',
    event_id BIGINT NOT NULL COMMENT '活动ID',
    ticket_type VARCHAR(50) NOT NULL COMMENT '票档名称',
    price DECIMAL(10, 2) NOT NULL COMMENT '单价',
    total_stock INT NOT NULL COMMENT '初始库存',
    available_stock INT NOT NULL COMMENT '当前可用库存',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ticket_stock_event_type (event_id, ticket_type),
    KEY idx_ticket_stock_event_id (event_id)
) ENGINE=InnoDB COMMENT='票档库存表';
```

为什么把票档拆成独立表：

```text
一个活动可以增加任意数量票档
不需要给活动表增加 vip_stock、normal_stock 等列
每个票档可以独立定价和维护库存
```

金额必须使用 `DECIMAL`，Java中对应 `BigDecimal`，不能使用 `double`。

## 9. 创建订单表

```sql
CREATE TABLE ticket_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '订单ID',
    order_no VARCHAR(40) NOT NULL COMMENT '业务订单号',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    event_id BIGINT NOT NULL COMMENT '活动ID',
    stock_id BIGINT NOT NULL COMMENT '票档ID',
    event_name VARCHAR(100) NOT NULL COMMENT '活动名称快照',
    ticket_type VARCHAR(50) NOT NULL COMMENT '票档名称快照',
    unit_price DECIMAL(10, 2) NOT NULL COMMENT '下单单价快照',
    quantity INT NOT NULL DEFAULT 1 COMMENT '购买数量',
    total_amount DECIMAL(10, 2) NOT NULL COMMENT '订单总金额',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '订单状态',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    pay_time DATETIME NULL,
    cancel_time DATETIME NULL,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ticket_order_order_no (order_no),
    UNIQUE KEY uk_ticket_order_user_stock (user_id, stock_id),
    KEY idx_ticket_order_user_time (user_id, create_time),
    KEY idx_ticket_order_stock_id (stock_id),
    KEY idx_ticket_order_status_time (status, create_time)
) ENGINE=InnoDB COMMENT='票务订单表';
```

订单保存活动名、票档名和价格快照，避免活动或价格修改后历史订单发生变化。

`uk_ticket_order_user_stock` 表示第一版业务规则：同一用户对同一票档只能成功创建一张订单。

## 10. 本地配置

默认配置保留公共内容：

```yaml
spring:
  application:
    name: flash-ticket
  profiles:
    active: local

server:
  port: 8080
```

创建 `application-local.yml`，并确保它被 `.gitignore` 忽略：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/flash_ticket?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
    username: root
    password: 你的本地密码
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout: 30000

mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
  global-config:
    banner: false
```

注意：

- 本地密码不要提交到Git。
- SQL日志只在学习和开发环境开启。
- 生产环境不能输出所有SQL和参数。

## 11. 连接测试

创建测试类：

```java
@SpringBootTest
class DataSourceConnectionTests {

    @Autowired
    private DataSource dataSource;

    @Test
    void shouldConnectToMySql() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1");
             ResultSet resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next());
            assertEquals(1, resultSet.getInt(1));
        }
    }
}
```

测试层可以直接使用 `DataSource`，因为它验证的是基础设施，不是业务流程。

## 12. 实现顺序

### 步骤1：加入依赖并构建

```powershell
.\mvnw.cmd clean test
```

此时没有数据库配置会启动失败，因此应紧接着完成本地配置。

### 步骤2：创建数据库和四张表

执行 `sql/01-schema.sql`，然后检查：

```sql
SHOW TABLES;
SHOW INDEX FROM ticket_order;
```

### 步骤3：创建本地Profile配置

确认URL、用户名、密码和时区正确。

### 步骤4：运行DataSource测试

```powershell
.\mvnw.cmd -Dtest=DataSourceConnectionTests test
```

### 步骤5：运行全部测试

```powershell
.\mvnw.cmd clean test
```

只有数据库连接测试和原有上下文测试都通过后，才能进入CRUD阶段。

## 13. 本阶段为什么没有接口

本阶段只负责数据基础设施。不要创建：

```text
GET /api/database/test
GET /api/mysql/ping
```

数据库连接属于内部能力，不应该暴露为业务HTTP接口。连接验证由自动化测试完成。

## 14. 阶段验收清单

- [ ] 数据库字符集为 `utf8mb4`。
- [ ] 四张表均为InnoDB。
- [ ] 唯一索引和普通索引存在。
- [ ] `application-local.yml` 未被Git跟踪。
- [ ] Spring Boot可以创建Hikari连接池。
- [ ] `SELECT 1` 测试通过。
- [ ] 项目没有新增业务接口。

## 15. 常见错误

### Communications link failure

检查MySQL是否启动、端口是否为3306、URL是否正确。

### Access denied

检查用户名、密码以及用户是否有 `flash_ticket` 权限。

### Public Key Retrieval is not allowed

本地开发URL可增加：

```text
allowPublicKeyRetrieval=true
```

### 时区错误

明确配置：

```text
serverTimezone=Asia/Shanghai
```

## 16. 下一阶段

下一阶段先实现活动CRUD，每完成一个接口都通过Postman、Service测试和数据库结果验证，再实现票档接口。

## 17. 官方参考

- Spring Boot SQL Databases：`https://docs.spring.io/spring-boot/3.5/reference/data/sql.html`
- MyBatis-Plus安装：`https://baomidou.com/en/getting-started/install/`
- MySQL 8.4 Reference：`https://dev.mysql.com/doc/refman/8.4/en/`

## 18. 完整代码答案

本节以第1阶段为基础。完整替换 `pom.xml` 和 `application.yml`，新增建表脚本与数据库连接测试。密码不写死在Git中，通过环境变量传入。

### 文件1：`pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.5.16</version>
        <relativePath/>
    </parent>
    <groupId>com.example</groupId>
    <artifactId>flash-ticket</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>FlashTicket</name>
    <properties>
        <java.version>17</java.version>
        <mybatis-plus.version>3.5.18</mybatis-plus.version>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
            <version>${mybatis-plus.version}</version>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

### 文件2：`src/main/resources/application.yml`

```yaml
spring:
  application:
    name: flash-ticket
  datasource:
    url: ${DB_URL:jdbc:mysql://localhost:3306/flash_ticket?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai}
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:root}
    driver-class-name: com.mysql.cj.jdbc.Driver
server:
  port: ${SERVER_PORT:8080}
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
```

### 文件3：`sql/01-schema.sql`

```sql
CREATE DATABASE IF NOT EXISTS flash_ticket
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE flash_ticket;

CREATE TABLE IF NOT EXISTS app_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL,
    phone VARCHAR(20) NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_phone (phone)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ticket_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    venue VARCHAR(100) NOT NULL,
    sale_start_time DATETIME NOT NULL,
    sale_end_time DATETIME NOT NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0草稿 1上架 2下架',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ticket_stock (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id BIGINT NOT NULL,
    tier_name VARCHAR(50) NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    total_stock INT NOT NULL,
    available_stock INT NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_event_tier (event_id, tier_name),
    KEY idx_stock_event_id (event_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ticket_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_no VARCHAR(40) NOT NULL,
    user_id BIGINT NOT NULL,
    event_id BIGINT NOT NULL,
    stock_id BIGINT NOT NULL,
    event_name VARCHAR(100) NOT NULL,
    tier_name VARCHAR(50) NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0待支付 1已支付 2已取消',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_order_no (order_no),
    UNIQUE KEY uk_user_stock (user_id, stock_id),
    KEY idx_order_user_id (user_id),
    KEY idx_order_stock_id (stock_id)
) ENGINE=InnoDB;
```

暂时不创建外键。学习项目通过Service校验关联关系，后续压测时也更容易观察每一条SQL。唯一索引仍然保留，因为它们是重复数据的最终防线。

### 文件4：`src/test/java/com/example/flashticket/DatabaseConnectionTests.java`

```java
package com.example.flashticket;

import static org.assertj.core.api.Assertions.assertThat;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class DatabaseConnectionTests {
    @Autowired
    private DataSource dataSource;

    @Test
    void shouldConnectToFlashTicketDatabase() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT DATABASE()")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("flash_ticket");
        }
    }
}
```

### 完整验证顺序

1. 在MySQL客户端执行 `sql/01-schema.sql`。
2. 如果密码不是 `root`，在PowerShell执行 `$env:DB_PASSWORD='你的密码'`。
3. 执行 `.\mvnw.cmd test -Dtest=DatabaseConnectionTests`。
4. 测试通过后再进入第3阶段。

