---
description: 'shrimp-task-manager로 태스크/기능 구현 계획을 수립합니다'
allowed-tools: ['mcp__shrimp-task-manager__plan_task', 'Read', 'Grep', 'Glob']
argument-hint: '<계획할 태스크·기능 설명> [-- 요구사항/제약]'
---

# Claude 명령어: Plan Task

shrimp-task-manager(MCP 기반 태스크 관리 도구)의 `plan_task`로 태스크·기능 구현 계획을 수립합니다. 가정·추측 없이 실제 코드베이스를 조사한 근거로만 계획을 세웁니다.

## 사용법

```
/plan-task <계획할 태스크·기능 설명>
/plan-task <설명> -- <기술 요구사항·제약 조건>
```

예: `/plan-task Task 013 보유 종목 평단가 재계산 API 추가 -- 낙관적 잠금 적용, NUMERIC(28,8) 유지`

## 프로세스

1. `$ARGUMENTS`를 `--` 기준으로 분리
   - 앞부분 → `description` (태스크 목표·배경·기대 결과를 담은 완전한 설명, 최소 10자)
   - 뒷부분(있으면) → `requirements` (기술 요구사항·비즈니스 제약·품질 기준)
2. 관련 코드베이스를 Read/Grep/Glob으로 실제 조사 — 가정이나 추측으로 채우지 않음
3. 기존 태스크를 참고할 필요가 있으면(연속 계획, 기존 태스크 조정 등) `existingTasksReference: true`로 호출
4. `plan_task` 호출 후 반환된 단계별 지침을 그대로 따라 계획을 정리
5. 도구가 이어서 `analyze_task`나 `split_tasks` 호출을 요구하면, 사용자에게 확인 후 다음 단계로 진행
