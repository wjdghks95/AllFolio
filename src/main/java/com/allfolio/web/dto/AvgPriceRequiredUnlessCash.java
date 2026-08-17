package com.allfolio.web.dto;

import com.allfolio.domain.AssetType;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * assetType == CASH면 avgPrice는 null이어도 통과(서버가 1로 대체), 그 외 타입이면 avgPrice가
 * null이면 실패한다 (docs/ROADMAP.md Task 006 확정 결정 #1). avgPrice의 필수 여부가 다른 필드
 * (assetType)에 좌우되므로 필드 단위 제약으로는 표현할 수 없어 레코드 전체를 대상으로 하는
 * 클래스 레벨 제약으로 작성한다.
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = AvgPriceRequiredUnlessCash.Validator.class)
public @interface AvgPriceRequiredUnlessCash {

    String message() default "현금이 아닌 자산은 평단가를 입력해야 합니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<AvgPriceRequiredUnlessCash, CreateAssetRequest> {

        @Override
        public boolean isValid(CreateAssetRequest value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            if (value.assetType() == AssetType.CASH) {
                return true;
            }
            return value.avgPrice() != null;
        }
    }
}
