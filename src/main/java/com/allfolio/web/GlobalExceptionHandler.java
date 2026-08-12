package com.allfolio.web;

import com.allfolio.domain.exception.EmailAlreadyExistsException;
import com.allfolio.domain.exception.InvalidCredentialsException;
import com.allfolio.web.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.jspecify.annotations.Nullable;

import java.util.stream.Collectors;

/**
 * 에러 포맷 단일화 지점. 응답은 항상 {code, message, timestamp} 3필드 (PHASE1_PLAN.md Step 3 「에러 응답 포맷」).
 *
 * <p>ResponseEntityExceptionHandler를 상속해 Spring MVC가 판정한 프로토콜 수준 상태코드
 * (405/415/406/404 등)를 보존한 채 본문만 에러 응답 포맷(PHASE1_PLAN.md Step 3)으로 바꾼다. 상속하지 않고 Exception 폴백만
 * 두면 ExceptionHandlerExceptionResolver가 DefaultHandlerExceptionResolver보다 먼저 동작해
 * 프로토콜 예외까지 전부 500으로 뒤바뀐다.
 *
 * <p>상위 클래스의 {@code handleException}이 20종 MVC 예외에 이미 @ExceptionHandler로 매핑돼 있으므로
 * 그 타입들(MethodArgumentNotValid, HandlerMethodValidation, HttpMessageNotReadable, NoResourceFound 등)에
 * 독립 @ExceptionHandler 메서드를 추가하면 "Ambiguous @ExceptionHandler method mapped"로 기동이 실패한다.
 * 반드시 protected 훅을 오버라이드할 것.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(this::describe)
                .collect(Collectors.joining(", "));
        return handleExceptionInternal(ex, message, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = ex.getAllErrors().stream()
                .map(error -> error.getDefaultMessage() == null ? "잘못된 값입니다." : error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return handleExceptionInternal(ex, message, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(ex, "요청 본문을 해석할 수 없습니다.", headers, status, request);
    }

    /**
     * 매핑되지 않은 경로. 도메인 404(Step 4의 ASSET_NOT_FOUND)와 구분되는 프로토콜 수준 404다.
     */
    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(ex, "요청한 경로를 찾을 수 없습니다.", headers, status, request);
    }

    /**
     * 상위 클래스가 처리하는 모든 MVC 예외의 최종 수렴 지점. body가 String이면 상세 메시지로 쓰고,
     * 상위 기본 경로처럼 null이면 상태코드별 기본 문구를 채운다.
     *
     * <p>응답 Content-Type을 application/json으로 못박는 이유: 이걸 비워두면 Spring이 요청의 Accept
     * 헤더와 등록된 컨버터를 협상하는데, 겹치는 타입이 없으면(예: Accept: text/csv) 본문 작성을 통째로
     * 포기해 406 응답이 빈 본문으로 나간다. 그러면 "항상 3필드" 계약이 깨진다.
     * AbstractMessageConverterMethodProcessor.writeWithMessageConverters는 응답에 concrete
     * Content-Type이 미리 설정돼 있으면 Accept 협상 자체를 건너뛰고 그 타입으로 직렬화하므로,
     * 이 한 줄로 클라이언트 Accept와 무관하게 JSON 본문(PHASE1_PLAN.md Step 3 「에러 응답 포맷」)이 보장된다.
     *
     * <p>인자로 받은 headers를 직접 수정하지 않고 복사본을 쓰는 이유: 상위 handleException은
     * ErrorResponse 계열 예외(MethodArgumentNotValid 등)에 대해 예외 객체 자신의 getHeaders()를
     * 넘기는데, 그 기본 구현이 읽기 전용인 HttpHeaders.EMPTY(ReadOnlyHttpHeaders)다.
     * 여기에 setContentType을 호출하면 UnsupportedOperationException이 터져 이 핸들러가 통째로
     * 실패하고, DefaultHandlerExceptionResolver의 sendError 폴백으로 넘어가 400이 빈 본문이 된다.
     * copyOf는 가변 사본을 만들면서 405의 Allow, 415/406의 Accept 헤더도 그대로 보존한다.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (statusCode.is5xxServerError()) {
            log.error("MVC 예외 처리 결과 500 응답", ex);
        }
        String message = body instanceof String s ? s : defaultMessage(statusCode);
        ErrorResponse errorResponse = ErrorResponse.of(errorCode(statusCode), message);
        HttpHeaders jsonHeaders = HttpHeaders.copyOf(headers);
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        return super.handleExceptionInternal(ex, errorResponse, jsonHeaders, statusCode, request);
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleEmailAlreadyExists(EmailAlreadyExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of("EMAIL_ALREADY_EXISTS", e.getMessage()));
    }

    /**
     * 현재 유니크 제약은 uk_users_email 하나뿐이라 EMAIL_ALREADY_EXISTS로 단정할 수 있다.
     * TODO Step 4에서 holdings의 uk_holdings_asset_id 등 다른 제약 위반이 들어오므로
     *      제약명(ConstraintViolationException.getConstraintName())으로 코드를 세분화해야 한다.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        log.warn("데이터 무결성 제약 위반", e);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of("EMAIL_ALREADY_EXISTS", "이미 가입된 이메일입니다."));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of("INVALID_CREDENTIALS", e.getMessage()));
    }

    /** 필터 체인에서 던져진 뒤 RestAuthenticationEntryPoint가 위임해 온다. */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of("UNAUTHORIZED", "인증이 필요합니다."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of("INTERNAL_ERROR", "서버 내부 오류가 발생했습니다."));
    }

    private String errorCode(HttpStatusCode status) {
        if (status.value() == HttpStatus.BAD_REQUEST.value()) return "VALIDATION_ERROR";
        if (status.value() == HttpStatus.NOT_FOUND.value()) return "NOT_FOUND";
        if (status.value() == HttpStatus.METHOD_NOT_ALLOWED.value()) return "METHOD_NOT_ALLOWED";
        if (status.value() == HttpStatus.NOT_ACCEPTABLE.value()) return "NOT_ACCEPTABLE";
        if (status.value() == HttpStatus.UNSUPPORTED_MEDIA_TYPE.value()) return "UNSUPPORTED_MEDIA_TYPE";
        if (status.is4xxClientError()) return "CLIENT_ERROR";
        return "INTERNAL_ERROR";
    }

    private String defaultMessage(HttpStatusCode status) {
        if (status.value() == HttpStatus.BAD_REQUEST.value()) return "잘못된 요청입니다.";
        if (status.value() == HttpStatus.NOT_FOUND.value()) return "요청한 경로를 찾을 수 없습니다.";
        if (status.value() == HttpStatus.METHOD_NOT_ALLOWED.value()) return "지원하지 않는 HTTP 메서드입니다.";
        if (status.value() == HttpStatus.NOT_ACCEPTABLE.value()) return "지원하지 않는 응답 형식입니다.";
        if (status.value() == HttpStatus.UNSUPPORTED_MEDIA_TYPE.value()) return "지원하지 않는 미디어 타입입니다.";
        if (status.is4xxClientError()) return "처리할 수 없는 요청입니다.";
        return "서버 내부 오류가 발생했습니다.";
    }

    private String describe(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }
}
