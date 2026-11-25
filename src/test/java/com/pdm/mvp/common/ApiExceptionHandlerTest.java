package com.pdm.mvp.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.server.ResponseStatusException;

/** API 异常兜底：500 必须留痕（带异常栈），且不把内部异常信息泄露给调用方。 */
@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void unexpectedExceptionReturnsSanitized500AndIsLoggedWithStackTrace(CapturedOutput output) {
        ProblemDetail detail = handler.unexpected(new IllegalStateException("boom-internal-detail"));

        assertEquals(500, detail.getStatus());
        assertEquals("服务暂时无法处理请求", detail.getDetail());
        assertTrue(output.getOut().contains("Unhandled exception while processing request"), output.getOut());
        assertTrue(output.getOut().contains("boom-internal-detail"), "日志必须带上原始异常");
        assertTrue(output.getOut().contains("IllegalStateException"), "日志必须带异常类型与栈");
        assertFalse(String.valueOf(detail.getDetail()).contains("boom-internal-detail"), "响应不得回显内部异常信息");
        assertEquals(500, handler.unexpected(new IllegalStateException("x")).getStatus());
    }

    @Test
    void clientErrorsAreNotTouchedByTheCatchAll(CapturedOutput output) {
        ProblemDetail status = handler.status(new ResponseStatusException(HttpStatus.BAD_REQUEST, "测点不存在"));

        assertEquals(HttpStatus.BAD_REQUEST.value(), status.getStatus());
        assertEquals("测点不存在", status.getDetail());
        assertFalse(output.getOut().contains("Unhandled exception"), "4xx 不得走兜底 500 日志");
    }
}
