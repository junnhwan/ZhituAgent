package com.zhituagent.common.error;

import com.zhituagent.api.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    @ResponseBody
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiErrorResponse handleApiException(ApiException exception, HttpServletRequest request) {
        return new ApiErrorResponse(
                exception.getErrorCode().name(),
                exception.getMessage(),
                (String) request.getAttribute("requestId")
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseBody
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiErrorResponse handleValidationException(MethodArgumentNotValidException exception,
                                                      HttpServletRequest request) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("validation failed");
        return new ApiErrorResponse(ErrorCode.INVALID_ARGUMENT.name(), message, (String) request.getAttribute("requestId"));
    }

    @ExceptionHandler(Exception.class)
    @ResponseBody
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiErrorResponse handleUnexpectedException(Exception exception, HttpServletRequest request) {
        return new ApiErrorResponse(ErrorCode.INTERNAL_ERROR.name(), exception.getMessage(), (String) request.getAttribute("requestId"));
    }
}
