package com.pdm.mvp.common;

import java.time.Instant;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail status(ResponseStatusException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(exception.getStatusCode(), exception.getReason());
        detail.setProperty("timestamp", Instant.now());
        return detail;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException exception) {
        var fields = exception.getBindingResult().getFieldErrors().stream()
                .collect(java.util.stream.Collectors.toMap(e -> e.getField(), e -> e.getDefaultMessage(), (a, b) -> a));
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "请求参数校验失败");
        detail.setProperty("fields", fields);
        detail.setProperty("timestamp", Instant.now());
        return detail;
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden(AccessDeniedException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "当前账号没有执行此操作的权限");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    ProblemDetail malformedRequest(Exception exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "请求数据格式或参数不正确");
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    ProblemDetail integrity(org.springframework.dao.DataIntegrityViolationException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "数据冲突，请检查编码唯一性和关联关系");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception exception) {
        // 兜底 500 必须留痕：否则未预期异常对用户只表现为“服务暂时无法处理请求”，日志里无从排查
        log.error("Unhandled exception while processing request", exception);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "服务暂时无法处理请求");
        detail.setProperty("timestamp", Instant.now());
        return detail;
    }
}
