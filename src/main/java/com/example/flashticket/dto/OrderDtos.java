package com.example.flashticket.dto;

import jakarta.validation.constraints.*;

public class OrderDtos {
    private OrderDtos (){};
    public record CreateUserRequest(@NotBlank @Size(max = 50) String username,
                                     @NotBlank @Pattern(regexp="^1\\d{10}$") String phone){}
    public record CreateOrderRequest(@NotNull @Positive Long userId,@NotNull @Positive Long stockId){}
}
