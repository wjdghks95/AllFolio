---
description: 'shrimp-task-manager 태스크를 조회해 가이드에 따라 구현합니다'
allowed-tools:
  [
    'mcp__shrimp-task-manager__execute_task',
    'mcp__shrimp-task-manager__query_task',
    'mcp__shrimp-task-manager__get_task_detail',
    'Read',
    'Grep',
    'Glob',
    'Edit',
    'Write',
    'Bash',
  ]
argument-hint: '<taskId | 태스크명·키워드>'
---

# Claude 명령어: Execute Task

shrimp-task-manager(MCP 기반 태스크 관리 도구)의 `execute_task`로 지정한 태스크의 실행 가이드를 받아, 그 가이드를 따라 실제 구현까지 진행합니다.

`execute_task` 호출 자체는 태스크 완료를 의미하지 않습니다. 도구가 반환하는 단계별 지침을 그대로 따라야 구현이 완료됩니다.

## 사용법

```
/execute-task <taskId>
/execute-task <태스크명 또는 키워드>
```

예: `/execute-task Task 013 평단가 재계산` 또는 UUID 직접 지정

## 프로세스

1. `$ARGUMENTS`가 UUID 형식(`xxxxxxxx-xxxx-4xxx-[89ab]xxx-xxxxxxxxxxxx`)이면 그대로 `taskId`로 사용
2. UUID가 아니면 `query_task`(키워드 모드)로 검색해 대상 태스크의 ID를 확정 — 검색 결과가 여러 개면 사용자에게 확인
3. `execute_task`로 taskId 호출 → 반환된 실행 가이드 확보
4. 가이드에 지침이 불충분하면 `get_task_detail`로 전체 구현 가이드·검증 기준 확인
5. 가이드 지침을 그대로 따라 실제 코드 구현 (Read/Grep/Glob으로 기존 구조 파악 후 Edit/Write/Bash로 구현·검증)
6. 구현 완료 후, 다음 단계로 `/verify-task` 실행이 필요함을 안내 (이 커맨드는 구현까지만 담당, 검증·완료 처리는 하지 않음)
