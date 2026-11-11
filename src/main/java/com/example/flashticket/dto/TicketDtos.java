package com.example.flashticket.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public final class TicketDtos {
    private TicketDtos(){}
    public record CreateEventRequest(@NotBlank String name,@NotBlank String venue,
    @NotNull @Future LocalDateTime saleStartTime,@NotNull @Future LocalDateTime saleEndTime
    ){}
    private record UpdateEventRequest(@NotBlank String name,@NotBlank String venue,
                                      @NotBlank LocalDateTime saleStartTime,@NotBlank LocalDateTime saleEndTime){}
    private record ChangeEventStatusRequest(@NotNull @Min(0) @Max(2) Integer status){

    }
    private record CreateStockRequest(@NotBlank String tierName, @NotNull @DecimalMax("0.01")BigDecimal price,@NotNull @Max(1) Integer totalStock){}
}
