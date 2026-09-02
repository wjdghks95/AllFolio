---
description: 'shrimp-task-manager 태스크의 구현 결과를 검증 기준에 따라 채점하고 완료 처리합니다'
allowed-tools:
  [
    'mcp__shrimp-task-manager__verify_task',
    'mcp__shrimp-task-manager__get_task_detail',
    'mcp__shrimp-task-manager__query_task',
    'Read',
    'Grep',
    'Glob',
    'Bash',
  ]
argument-hint: '<taskId | 태스크명·키워드>'
---

# Claude 명령어: Verify Task

shrimp-task-manager(MCP 기반 태스크 관리 도구)의 `verify_task`로 지정한 태스크의 구현 결과를 검증 기준(verificationCriteria)에 따라 채점합니다. 점수가 80점 이상이면 태스크가 자동으로 완료 처리됩니다.

이 커맨드는 `/execute-task`로 구현을 마친 태스크를 검증하는 용도입니다 — 구현 자체는 하지 않습니다.

## 사용법

```
/verify-task <taskId>
/verify-task <태스크명 또는 키워드>
```

예: `/verify-task assetApi.ts 신규 작성` 또는 UUID 직접 지정

## 프로세스

1. `$ARGUMENTS`가 UUID 형식(`xxxxxxxx-xxxx-4xxx-[89ab]xxx-xxxxxxxxxxxx`)이면 그대로 `taskId`로 사용
2. UUID가 아니면 `query_task`(키워드 모드)로 검색해 대상 태스크의 ID를 확정 — 검색 결과가 여러 개면 사용자에게 확인
3. `get_task_detail`로 해당 태스크의 `verificationCriteria`·`implementationGuide`·`relatedFiles`를 전체 확보
4. `verificationCriteria`에 적힌 항목 하나하나를 실제로 재확인한다 — 이전 대화의 "됐다"는 진술이나 기억에 의존하지 않고, 각 항목에 맞는 도구로 직접 실측한다
   - 타입·빌드 통과 여부 → `Bash`로 해당 명령(`tsc -b --noEmit`, `npm run build` 등) 재실행
   - 테스트 통과 여부 → `Bash`로 테스트 명령 재실행(전체 스위트가 간헐적으로 실패하는 기존 인프라 이슈가 있다면 `.claude/rules/testing.md` 안내대로 해당 파일만 단독 실행해 격리)
   - 특정 코드 패턴의 존재/부재 → `Grep`
   - 특정 파일의 생성/삭제 여부 → `Glob` 또는 `Read`
   - 구현 내용이 가이드와 일치하는지 → `Read`로 실제 코드 대조
5. 재확인 결과를 근거로 `verify_task` 도구의 4개 채점 기준에 따라 `score`(0~100)를 산정한다: 요구사항 준수 30% · 기술 품질 30% · 통합 호환성 20% · 성능 확장성 20%
6. `summary`를 score 기준에 맞춰 작성한다(최소 30자)
   - 80점 이상: 구현 결과와 주요 결정을 간결히 요약(무엇을 만들었는지 + 왜 그렇게 했는지)
   - 80점 미만: 무엇이 미비한지와 어떻게 고쳐야 하는지를 구체적으로 서술
7. `verify_task` 호출 → 결과 보고
   - 80점 이상: 태스크가 자동 완료 처리됐음을 알리고, 의존하던 다음 태스크가 있으면 안내
   - 80점 미만: 미비점을 사용자에게 보고하고, 수정 후 재검증이 필요함을 안내(자동으로 수정에 들어가지 않는다 — 사용자 확인 후 진행)
