---
description: 'shrimp-task-manager에 등록된 태스크 목록을 조회합니다'
allowed-tools: ['mcp__shrimp-task-manager__list_tasks']
argument-hint: '[all|pending|in_progress|completed]'
---

# Claude 명령어: List Tasks

shrimp-task-manager(MCP 기반 태스크 관리 도구)에 등록된 태스크 목록을 상태별로 조회합니다.

## 사용법

```
/list-tasks
/list-tasks pending
/list-tasks in_progress
/list-tasks completed
```

인자를 생략하면 전체(`all`) 상태를 조회합니다.

## 프로세스

1. `$ARGUMENTS`가 `pending`/`in_progress`/`completed` 중 하나면 해당 상태로, 없으면 `all`로 `list_tasks` 호출
2. 결과를 태스크명 · 상태 · 의존성 · 설명 요약 형태로 정리해서 출력
3. 태스크가 없으면 "등록된 태스크 없음"으로 안내
