# Github Issue Discord Relay

GitHub 저장소에 새 이슈가 열리면 Discord 채널로 이슈 제목과 링크를 보내는 Webhook 중계 서비스다.

**목표: GitHub에 성공 응답을 준 이벤트는 서버가 죽거나 Discord가 불안정해도 잃어버리지 않고 전달한다.**

- 서비스: http://3.34.217.173 (수신·발송 이력 화면)
- GitHub: https://github.com/chunhj/issue-discord-relay
- 문서: [전달 보장과 중복 발송](docs/delivery-semantics.md) · [배포와 운영](docs/deployment.md)

## 주요 기능

- GitHub Webhook 수신 (HMAC 서명 검증)
- Discord 발송과 실패 시 자동 재시도
- 수신·발송 이력 조회 화면 (상태 필터, 5초마다 자동 갱신)

## 실행 화면

GitHub에 이슈를 열면 Discord 채널에 이슈 번호, 제목, 저장소 이름이 담긴 메시지가 도착한다.

<img width="653" height="181" alt="Discord로 전달된 이슈 알림" src="https://github.com/user-attachments/assets/2974ff5d-cab1-4768-8f1f-a9fb842678f9" />

<br>같은 건이 이력 화면에 수신 시각, 발송 상태, 시도 횟수, 전달까지 걸린 시간과 함께 기록된다.

<img width="1221" height="82" alt="이력 화면의 발송 완료 기록" src="https://github.com/user-attachments/assets/e3704dcf-1491-48ed-b14a-eb98ee967b4a" />

## 핵심 기술

### 1. 응답 전 DB 저장으로 이벤트 유실 방지

- **문제**: GitHub는 성공 응답을 받은 이벤트를 다시 보내지 않는다. 응답 후에 처리하다 서버가 죽으면 이벤트가 사라진다.
- **해결**: 이벤트와 발송 작업을 한 트랜잭션으로 저장하고, 커밋이 끝난 뒤에만 응답한다. Discord 발송은 백그라운드 워커가 따로 처리한다.
- **결과**: Discord가 멈춰 있어도 수신은 영향을 받지 않고, 저장된 작업은 서버가 재시작된 뒤 이어서 발송된다.

### 2. DB 제약으로 중복 수신 처리

- **문제**: GitHub는 같은 이벤트를 다시 보낼 수 있다. "조회 후 없으면 저장" 방식은 동시에 들어온 요청을 둘 다 통과시킨다.
- **해결**: 이벤트 고유 ID에 PostgreSQL UNIQUE 제약을 걸고, 충돌하면 저장을 건너뛴다. 중복 판단을 애플리케이션이 아닌 DB에 맡겼다.
- **결과**: 같은 이벤트가 몇 번 와도 발송 작업은 하나만 생긴다.

### 3. PostgreSQL 작업 큐와 처리 기한(lease)

- **문제**: 워커 여러 개가 같은 작업을 집으면 중복 발송이 생기고, 작업을 잡은 서버가 죽으면 그 작업은 멈춘다.
- **해결**: 서버 한 대 규모라 메시지 큐를 따로 두지 않고 DB 테이블을 큐로 썼다. `FOR UPDATE SKIP LOCKED`로 다른 워커가 잡은 작업은 건너뛰고, 작업마다 처리 기한을 둬서 기한이 지나면 다른 워커가 회수한다. Discord 호출 중에는 DB 트랜잭션을 잡지 않는다.
- **결과**: 워커끼리 서로 기다리거나 같은 작업을 처리하지 않고, 죽은 서버의 작업도 자동으로 복구된다.

### 4. 실패 유형별 재시도와 중복 발송의 한계 정의

- **문제**: Discord는 일시 장애나 요청 한도 초과(429)를 낸다. 또 Discord 웹훅에는 멱등 키가 없어서, 발송 후 결과를 기록하기 전에 서버가 죽으면 같은 메시지가 다시 나갈 수 있다.
- **해결**: 일시 오류는 대기 시간을 늘려 가며 최대 5회 재시도하고, 429는 Discord가 알려 준 시간만큼 기다리며, 복구할 수 없는 오류는 바로 실패 처리한다. 전달 보장을 at-least-once로 정의하고, 중복 횟수를 시도 한도 안으로 제한했다.
- **결과**: 중복이 생기는 구간을 실제 프로세스 강제 종료 테스트로 재현하고 [문서](docs/delivery-semantics.md)로 정리했다.

## 검증 결과

실제 PostgreSQL(Testcontainers)과 모의 Discord 서버(WireMock)로 테스트하며, GitHub Actions에서 push마다 실행한다.

| 시나리오 | 결과 |
|----------|------|
| 같은 이벤트 동시 요청 100건 | 발송 작업 1건 생성 (중복 저장 0%) |
| 저장 직후 서버 강제 종료 → 재시작 | 이벤트 유실 0건, 발송 1회 |
| 워커 2개가 이슈 20건을 동시 처리 | 모든 이슈가 정확히 1회씩 발송 |

응답 시간과 처리량은 아직 측정하지 않았다.

## 아키텍처

```text
GitHub ──> 수신 API (서명 검증, 저장) ──> PostgreSQL <── 발송 워커 ──> Discord
브라우저 ──> 이력 조회 API ─────────────────┘
```

Docker Compose로 app(Spring Boot)과 PostgreSQL 컨테이너 두 개를 AWS EC2에 띄운다. React 화면은 Spring Boot가 함께 제공한다.

## 기술 스택

| 영역 | 기술 |
|------|------|
| Backend | Java 21, Spring Boot 4.1, Spring Data JPA, Flyway |
| Database | PostgreSQL |
| Frontend | React, TypeScript, Vite |
| Test | JUnit 5, Testcontainers, WireMock, Vitest |
| Infra | Docker Compose, AWS EC2, GitHub Actions (테스트 통과 후 자동 배포) |

## 실행 방법

Docker가 필요합니다.

```bash
cp .env.example .env   # 웹훅 시크릿, Discord URL, DB 비밀번호 입력
docker compose up -d --build
```

http://localhost 에서 화면을 엽니다. 테스트는 `backend/gradlew -p backend test`로 실행합니다. 서버 배포와 자동 배포 설정은 [배포와 운영](docs/deployment.md)에 있습니다.

## 배포

`main` 푸시 → GitHub Actions에서 백엔드 테스트, 프론트엔드 검사, Docker 이미지 빌드 → 모두 통과하면 SSH로 EC2에 접속해 테스트한 커밋을 받아 Docker Compose로 다시 빌드하고 실행합니다. 외부에는 80번 포트만 열고 DB 포트는 공개하지 않습니다. 설정: [.github/workflows/ci.yml](.github/workflows/ci.yml), 서버 준비와 시크릿 설정: [배포와 운영](docs/deployment.md)

## 개선 과제 (미구현)

- **중복 발송률 측정**: 중복이 생기는 구간은 재현했지만 발생 빈도는 측정하지 않았다. 발송 중 강제 종료를 반복하는 시나리오로 모의 Discord 서버의 수신 기록을 집계해 중복 비율을 수치로 보고할 계획이다.
- **실패 작업 재발송**: 지금은 실패로 끝난 작업을 다시 보내려면 DB를 직접 수정해야 한다. 기존 동시성 제어를 그대로 쓰는 재발송 API를 추가해 장애 복구를 서비스 안에서 끝내려 한다.
