package com.allfolio;

import com.allfolio.domain.AssetType;
import com.allfolio.domain.PrecisionScale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「금융 정밀도 규칙」(docs/ROADMAP.md 465-474행)을 PrecisionScale 유틸 기준으로 고정하고,
 * double/float 금지 규칙(CLAUDE.md)이 src/main/java에서 깨지지 않았는지 자동으로 검사한다
 * (docs/ROADMAP.md Task 016).
 */
class BigDecimalPrecisionTest {

    @ParameterizedTest
    @CsvSource({
            "STOCK, KRW, 0",
            "STOCK, USD, 4",
            "CASH, KRW, 0",
            "CASH, USD, 4",
            "COIN, KRW, 8",
            "COIN, USD, 8",
            "STOCK, JPY, 2",
    })
    void scaleForMatchesPrecisionRulesTable(AssetType assetType, String currency, int expectedScale) {
        assertThat(PrecisionScale.scaleFor(assetType, currency)).isEqualTo(expectedScale);
    }

    @ParameterizedTest
    @CsvSource({
            "KRW, 0",
            "USD, 4",
            "JPY, 2",
    })
    void scaleForCurrencyIgnoresAssetType(String currency, int expectedScale) {
        assertThat(PrecisionScale.scaleForCurrency(currency)).isEqualTo(expectedScale);
    }

    @Test
    void halfUpRoundsKrwScaleBoundaryUp() {
        BigDecimal value = new BigDecimal("100.5");
        int scale = PrecisionScale.scaleFor(AssetType.STOCK, "KRW");

        BigDecimal rounded = value.setScale(scale, RoundingMode.HALF_UP);

        assertThat(rounded).isEqualByComparingTo("101");
    }

    @Test
    void halfUpRoundsCoinScaleBoundaryUp() {
        BigDecimal value = new BigDecimal("0.000000005");
        int scale = PrecisionScale.scaleFor(AssetType.COIN, "KRW");

        BigDecimal rounded = value.setScale(scale, RoundingMode.HALF_UP);

        assertThat(rounded).isEqualByComparingTo("0.00000001");
    }

    /**
     * CLAUDE.md의 double/float 금지 규칙을 자동화한다. 코드에서 실제로 쓰인 것만 잡아야 하므로
     * 라인 주석(//)뿐 아니라 여러 줄에 걸치는 블록/Javadoc 주석(/* ... *&#47;)도 상태 추적으로
     * 걷어낸 뒤 검사한다 — 라인 단위로만 "//"를 잘라내면 "이 필드는 double 정밀도 문제가 있다"
     * 같은 Javadoc 설명 한 줄에도 오탐이 난다(code-reviewer 지적, docs/ROADMAP.md Task 016).
     */
    @Test
    void mainSourceContainsNoPrimitiveDoubleOrFloat() throws IOException {
        Pattern primitiveKeyword = Pattern.compile("\\b(double|float)\\b");
        Path mainSourceRoot = Path.of("src", "main", "java");

        List<String> violations;
        try (Stream<Path> files = Files.walk(mainSourceRoot)) {
            violations = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> findViolations(path, primitiveKeyword).stream())
                    .toList();
        }

        assertThat(violations).as("double/float 금지 규칙 위반: %s", violations).isEmpty();
    }

    private List<String> findViolations(Path javaFile, Pattern primitiveKeyword) {
        try {
            List<String> originalLines = Files.readAllLines(javaFile);
            String[] codeOnlyLines = stripComments(String.join("\n", originalLines)).split("\n", -1);

            return IntStream.range(0, originalLines.size())
                    .filter(i -> primitiveKeyword.matcher(codeOnlyLines[i]).find())
                    .mapToObj(i -> javaFile + ": " + originalLines.get(i).strip())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** "//" 라인 주석과 "/* ... *&#47;" 블록(Javadoc 포함) 주석을 공백으로 치환한다 — 줄 수는 그대로 유지. */
    private String stripComments(String source) {
        StringBuilder result = new StringBuilder(source.length());
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    result.append(c);
                } else {
                    result.append(' ');
                }
            } else if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    result.append("  ");
                    i++;
                } else {
                    result.append(c == '\n' ? '\n' : ' ');
                }
            } else if (c == '/' && next == '/') {
                inLineComment = true;
                result.append("  ");
                i++;
            } else if (c == '/' && next == '*') {
                inBlockComment = true;
                result.append("  ");
                i++;
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
}
