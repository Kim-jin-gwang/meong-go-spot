package com.meonggo.backend.global.error;

import com.meonggo.backend.auth.exception.AuthErrorCode;
import com.meonggo.backend.auth.exception.InputValidationException;
import com.meonggo.backend.auth.exception.RetryableAuthException;
import com.meonggo.backend.global.common.response.ApiResponse;
import com.meonggo.backend.global.common.response.ValidationError;
import com.meonggo.backend.global.common.response.ValidationErrorResponse;
import com.meonggo.backend.photo.exception.PhotoErrorCode;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 모든 오류를 ApiResponse 공통 형식으로 변환한다 (backend/docs/backend-common-settings.md).
 *
 * <p>Spring MVC 표준 예외는 ResponseEntityExceptionHandler의 개별 핸들러가 모두 {@link #handleExceptionInternal}로
 * 모이므로, 이 단일 지점에서 ErrorCode로 매핑한다. 예상하지 못한 예외는 스택 트레이스를 서버 로그에만 남기고 안전한 메시지만 반환한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InputValidationException.class)
    public ResponseEntity<ApiResponse<ValidationErrorResponse>> handleInputValidation(
            InputValidationException ex) {
        return validationErrorResponse(List.of(new ValidationError(ex.field(), ex.getMessage())));
    }

    @ExceptionHandler(RetryableAuthException.class)
    public ResponseEntity<ApiResponse<Void>> handleRetryableAuth(RetryableAuthException ex) {
        LOG.warn("Authentication dependency or capacity error: {}", ex.errorCode().code());
        return ResponseEntity.status(ex.errorCode().status())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()))
                .body(ApiResponse.error(ex.errorCode()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        ErrorCode errorCode = ex.errorCode();
        LOG.warn("Business error: {} ({})", errorCode.code(), ex.getMessage());
        return ResponseEntity.status(errorCode.status()).body(ApiResponse.error(errorCode));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        LOG.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity.status(AuthErrorCode.ACCESS_DENIED.status())
                .body(ApiResponse.error(AuthErrorCode.ACCESS_DENIED));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<ValidationErrorResponse>> handleConstraintViolation(
            ConstraintViolationException ex) {
        List<ValidationError> fieldErrors =
                ex.getConstraintViolations().stream()
                        .map(
                                violation ->
                                        new ValidationError(
                                                String.valueOf(violation.getPropertyPath()),
                                                violation.getMessage()))
                        .toList();
        return validationErrorResponse(fieldErrors);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        // 내부 예외 메시지·스택은 응답에 절대 포함하지 않는다.
        LOG.error("Unexpected error", ex);
        return ResponseEntity.status(CommonErrorCode.INTERNAL_SERVER_ERROR.status())
                .body(ApiResponse.error(CommonErrorCode.INTERNAL_SERVER_ERROR));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        if (ex instanceof MethodArgumentNotValidException validationEx) {
            List<ValidationError> fieldErrors =
                    validationEx.getBindingResult().getFieldErrors().stream()
                            .map(
                                    error ->
                                            new ValidationError(
                                                    error.getField(), error.getDefaultMessage()))
                            .toList();
            return asObjectResponse(validationErrorResponse(fieldErrors));
        }
        if (ex instanceof HandlerMethodValidationException validationEx) {
            List<ValidationError> fieldErrors =
                    validationEx.getParameterValidationResults().stream()
                            .flatMap(
                                    result ->
                                            result.getResolvableErrors().stream()
                                                    .map(
                                                            error ->
                                                                    new ValidationError(
                                                                            result.getMethodParameter()
                                                                                    .getParameterName(),
                                                                            error
                                                                                    .getDefaultMessage())))
                            .toList();
            return asObjectResponse(validationErrorResponse(fieldErrors));
        }

        ErrorCode errorCode = mapMvcError(ex, statusCode);
        LOG.warn("MVC error: {} ({})", errorCode.code(), ex.getClass().getSimpleName());
        return ResponseEntity.status(errorCode.status()).body(ApiResponse.error(errorCode));
    }

    private ErrorCode mapMvcError(Exception ex, HttpStatusCode statusCode) {
        if (ex instanceof MaxUploadSizeExceededException) {
            return PhotoErrorCode.TOO_LARGE;
        }
        if (ex instanceof HttpMessageNotReadableException) {
            return CommonErrorCode.MALFORMED_JSON;
        }
        if (ex instanceof MissingServletRequestParameterException) {
            return CommonErrorCode.MISSING_PARAMETER;
        }
        if (ex instanceof TypeMismatchException) {
            return CommonErrorCode.TYPE_MISMATCH;
        }
        if (ex instanceof HttpRequestMethodNotSupportedException) {
            return CommonErrorCode.METHOD_NOT_ALLOWED;
        }
        if (ex instanceof NoResourceFoundException) {
            return CommonErrorCode.RESOURCE_NOT_FOUND;
        }
        if (ex instanceof HttpMediaTypeNotSupportedException) {
            return CommonErrorCode.MEDIA_TYPE_NOT_SUPPORTED;
        }
        if (ex instanceof HttpMediaTypeNotAcceptableException) {
            return CommonErrorCode.NOT_ACCEPTABLE;
        }
        // 새 MVC 예외가 추가돼도 상태 코드 기준으로 안전하게 수렴시킨다.
        return switch (statusCode.value()) {
            case 404 -> CommonErrorCode.RESOURCE_NOT_FOUND;
            case 405 -> CommonErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> CommonErrorCode.NOT_ACCEPTABLE;
            case 415 -> CommonErrorCode.MEDIA_TYPE_NOT_SUPPORTED;
            case 500 -> CommonErrorCode.INTERNAL_SERVER_ERROR;
            default -> CommonErrorCode.INVALID_INPUT;
        };
    }

    private ResponseEntity<ApiResponse<ValidationErrorResponse>> validationErrorResponse(
            List<ValidationError> fieldErrors) {
        return ResponseEntity.status(CommonErrorCode.INVALID_INPUT.status())
                .body(
                        ApiResponse.error(
                                CommonErrorCode.INVALID_INPUT,
                                new ValidationErrorResponse(fieldErrors)));
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Object> asObjectResponse(ResponseEntity<?> response) {
        return (ResponseEntity<Object>) response;
    }
}
