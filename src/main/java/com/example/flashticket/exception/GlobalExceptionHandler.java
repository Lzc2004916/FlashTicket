package com.example.flashticket.exception;

import com.example.flashticket.common.ApiResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.method.MethodValidationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> business(BusinessException e){
        return ApiResponse.failure(e.getCode(),e.getMessage());
    }
    @ExceptionHandler(MethodValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> validation(MethodArgumentNotValidException e){
        String message = e.getBindingResult().getFieldErrors().stream()
            .map(x -> x.getField() + ": " + x.getDefaultMessage()).collect(Collectors.joining("; "));
        return ApiResponse.failure(400, message);
    }
    @ExceptionHandler(DuplicateKeyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> duplicate(DuplicateKeyException e){
        return ApiResponse.failure(409,"数据已存在，请勿重复提交");
    }
}
