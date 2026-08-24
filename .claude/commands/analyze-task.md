---
description: 'shrimp-task-manager로 태스크 요구사항과 코드베이스를 심층 분석합니다'
allowed-tools: ['mcp__shrimp-task-manager__analyze_task', 'Read', 'Grep', 'Glob']
argument-hint: '<분석할 태스크 설명>'
---

# Claude 명령어: Analyze Task

shrimp-task-manager(MCP 기반 태스크 관리 도구)의 `analyze_task`로 태스크 요구사항을 심층 분석합니다. 관련 코드베이스를 실제로 조사한 뒤, 기술적 실행 가능성과 리스크를 짚습니다.

## 사용법

```
/analyze-task <분석할 태스크 설명>
```

예: `/analyze-task Task 013 보유 종목 평단가 재계산 API 추가`

## 프로세스

1. `$ARGUMENTS`로 전달된 태스크 설명을 바탕으로 관련 코드베이스를 Read/Grep/Glob으로 조사 (기존 구조·컨벤션 파악)
2. 조사 결과를 근거로 다음 두 값을 구성
   - `summary`: 태스크 목표·범위·핵심 기술 과제를 담은 구조화된 요약 (최소 10자)
   - `initialConcept`: 기술 방안·아키텍처·구현 전략을 담은 초안 (최소 50자). 코드가 필요하면 완전한 코드 대신 의사코드(pseudocode)로 핵심 흐름만 제시
3. `analyze_task` 호출
4. 결과를 바탕으로 재분석이 필요하면 이전 분석 결과를 `previousAnalysis`로 넘겨 다시 호출
5. 최종 분석 결과(기술 방안, 리스크, 미해결 이슈)를 요약해서 보고
