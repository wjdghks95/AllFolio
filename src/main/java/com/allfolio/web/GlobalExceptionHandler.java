package com.allfolio.web;

import com.allfolio.domain.exception.AssetNotFoundException;
import com.allfolio.domain.exception.AvgPriceRequiredException;
import com.allfolio.domain.exception.EmailAlreadyExistsException;
import com.allfolio.domain.exception.ExternalPriceApiException;
import com.allfolio.domain.exception.InvalidCredentialsException;
import com.allfolio.domain.exception.PriceUnavailableException;
import com.allfolio.domain.exception.RefreshTokenInvalidException;
import com.allfolio.web.dto.ErrorResponse;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
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
 * 에러 포맷 단일화 지점. 응답은 항상 {code, message, timestamp} 3필드 (docs/ROADMAP.md Task 003 「에러 응답 포맷」).
 *
 * <p>ResponseEntityExceptionHandler를 상속해 Spring MVC가 판정한 프로토콜 수준 상태코드
 * (405/415/406/404 등)를 보존한 채 본문만 에러 응답 포맷(docs/ROADMAP.md Task 003)으로 바꾼다. 상속하지 않고 Exception 폴백만
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

    /**
     * getFieldErrors()만 모으면 클래스 레벨 제약({@link com.allfolio.web.dto.AvgPriceRequiredUnlessCash}
     * 등)의 위반이 통째로 사라진다 — 그 위반은 FieldError가 아니라 global(Object) error로 담기기
     * 때문이다(docs/ROADMAP.md Task 006 code-reviewer 지적). getAllErrors()로 두 종류를 함께 모은다.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = ex.getBindingResult().getAllErrors().stream()
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
     * 매핑되지 않은 경로. 도메인 404(Task 006에서 정의된 ASSET_NOT_FOUND)와 구분되는 프로토콜 수준 404다.
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
     * 이 한 줄로 클라이언트 Accept와 무관하게 JSON 본문(docs/ROADMAP.md Task 003 「에러 응답 포맷」)이 보장된다.
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
     * 제약명(원인 체인의 org.hibernate.exception.ConstraintViolationException#getConstraintName())으로
     * 분기한다. uk_users_email 위반만 EMAIL_ALREADY_EXISTS로 단정할 수 있고, holdings의
     * uk_holdings_asset_id 등 그 외 제약 위반은 매칭되는 도메인 에러 코드가 없으므로 일반 409
     * CONFLICT로 폴백한다 (docs/ROADMAP.md Task 006). 결정 #3(종목 중복 등록 허용)에 따라
     * assets에는 새 UNIQUE 제약이 없어 ASSET_ALREADY_EXISTS 코드는 만들지 않는다.
     *
     * <p>원인 메시지 문자열 매칭(contains) 대신 제약명 기반으로 전환한 이유: 이 Task부터
     * holdings 쪽 제약이 늘어나는데, 메시지 포맷은 DB 드라이버·로케일에 따라 달라질 수 있어
     * 문자열 포함 여부보다 제약명이 안정적인 판별 기준이다(docs/ROADMAP.md Task 012).
     * jakarta.validation.ConstraintViolationException(Bean Validation)과 이름이 같은
     * org.hibernate.exception.ConstraintViolationException(JDBC 예외 변환 결과)을 혼동하지
     * 않도록 주의 — getConstraintName()은 후자에만 있다.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        log.warn("데이터 무결성 제약 위반", e);
        Throwable cause = e.getMostSpecificCause();
        if (cause instanceof ConstraintViolationException cve && "uk_users_email".equals(cve.getConstraintName())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ErrorResponse.of("EMAIL_ALREADY_EXISTS", "이미 가입된 이메일입니다."));
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of("CONFLICT", "데이터 정합성 제약을 위반했습니다."));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of("INVALID_CREDENTIALS", e.getMessage()));
    }

    /** 없거나 이미 폐기(rotation·로그아웃)됐거나 만료된 Refresh Token. */
    @ExceptionHandler(RefreshTokenInvalidException.class)
    public ResponseEntity<ErrorResponse> handleRefreshTokenInvalid(RefreshTokenInvalidException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of("INVALID_REFRESH_TOKEN", e.getMessage()));
    }

    /** 자산이 없을 때와 남의 자산일 때 모두 같은 응답 — 403이면 "그 ID는 존재한다"는 사실이 새어 나간다. */
    @ExceptionHandler(AssetNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAssetNotFound(AssetNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of("ASSET_NOT_FOUND", e.getMessage()));
    }

    /**
     * PUT /v1/assets/{id}/holdings 대상이 CASH가 아닌데 avgPrice가 null인 경우.
     * POST의 AvgPriceRequiredUnlessCash 클래스 레벨 제약과 같은 규칙이라 동일하게 VALIDATION_ERROR로 응답한다.
     */
    @ExceptionHandler(AvgPriceRequiredException.class)
    public ResponseEntity<ErrorResponse> handleAvgPriceRequired(AvgPriceRequiredException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of("VALIDATION_ERROR", e.getMessage()));
    }

    /** CASH(KRW)처럼 시세 개념이 없는 자산에 대한 조회 요청. STOCK도 벤더 미확정으로 이 경로를 함께 쓴다(Task 021). */
    @ExceptionHandler(PriceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handlePriceUnavailable(PriceUnavailableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of("PRICE_NOT_APPLICABLE", e.getMessage()));
    }

    /** 외부 시세 API 최종 실패 또는 Circuit Breaker Open (Task 021). 캐시가 없어 직전 시세로 대체 응답할 수 없다. */
    @ExceptionHandler(ExternalPriceApiException.class)
    public ResponseEntity<ErrorResponse> handleExternalPriceApi(ExternalPriceApiException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of("EXTERNAL_API_DOWN", e.getMessage()));
    }

    /**
     * JPA가 @Version 불일치를 감지했을 때 던지는 예외. 충돌은 서버 잘못이 아니라 클라이언트가
     * 재시도하면 되는 상황이므로 500이 아닌 409로 응답한다. 하위 타입인
     * ObjectOptimisticLockingFailureException이 아닌 상위 타입으로 캐치한다 —
     * Spring Data의 delete/save 경로가 상황에 따라 상위 타입만 던질 수 있어, 하위 타입만
     * 잡으면 그 경우 Exception 폴백으로 떨어져 500이 나간다(code-reviewer 지적).
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLockingFailure(OptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of("HOLDING_CONFLICT", "다른 요청이 먼저 이 항목을 수정했습니다."));
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

    private String describe(ObjectError error) {
        if (error instanceof FieldError fieldError) {
            return fieldError.getField() + ": " + fieldError.getDefaultMessage();
        }
        return error.getDefaultMessage();
    }
}
