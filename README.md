# FlashTicket

FlashTicket is a Spring Boot learning project for building a distributed ticketing system step by step.

## Requirements

- Java 17
- Use the included Maven Wrapper; no global Maven installation is required.

## Build

```powershell
.\mvnw.cmd clean package
```

## Run

```powershell
.\mvnw.cmd spring-boot:run
```

The application starts on `http://localhost:8080`. No business endpoints are included in the initial project.

## Package Structure

```text
com.example.flashticket
|-- common
|-- config
|-- controller
|-- dto
|-- entity
|-- exception
|-- mapper
|-- service
`-- vo
```
