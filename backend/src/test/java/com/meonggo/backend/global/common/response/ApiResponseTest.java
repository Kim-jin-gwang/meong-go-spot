package com.meonggo.backend.global.common.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.global.error.CommonErrorCode;
import org.junit.jupiter.api.Test;

class ApiResponseTest {

    @Test
    void successCarriesFixedCodeAndData() {
        ApiResponse<String> response = ApiResponse.success("payload");

        assertThat(response.code()).isEqualTo("SUCCESS");
        assertThat(response.message()).isEqualTo("요청에 성공했습니다.");
        assertThat(response.data()).isEqualTo("payload");
    }

    @Test
    void successAllowsCustomMessage() {
        ApiResponse<String> response = ApiResponse.success("회원 생성에 성공했습니다.", "payload");

        assertThat(response.code()).isEqualTo("SUCCESS");
        assertThat(response.message()).isEqualTo("회원 생성에 성공했습니다.");
    }

    @Test
    void errorCarriesErrorCodeAndOmitsData() {
        ApiResponse<Void> response = ApiResponse.error(CommonErrorCode.RESOURCE_NOT_FOUND);

        assertThat(response.code()).isEqualTo("COMMON-404");
        assertThat(response.message()).isEqualTo("요청한 리소스를 찾을 수 없습니다.");
        assertThat(response.data()).isNull();
    }

    @Test
    void businessExceptionKeepsItsErrorCode() {
        BusinessException exception = new BusinessException(CommonErrorCode.INVALID_INPUT);

        assertThat(exception.errorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT);
        assertThat(exception.getMessage()).isEqualTo(CommonErrorCode.INVALID_INPUT.message());
    }
}
