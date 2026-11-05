# 전달 보장 범위와 중복 발송 구간

이 문서는 Phase 2(재시도와 장애 복구)까지 구현된 서비스가 무엇을 보장하고 무엇을 보장하지 않는지, 중복 발송이 어떤 조건에서 생기는지, 그것을 어떻게 재현하는지를 정리한다. 설계 문서의 6.5(서버 재시작 후 복구)와 6.6(Exactly-once 보장의 한계)을 구현과 테스트로 확인한 결과다.

## 1. 요약

- 이 서비스는 **at-least-once** 전달이다. GitHub에 2xx를 돌려준 이벤트는 유실되지 않고 최소 한 번은 Discord로 전달을 시도한다.
- 중복 발송은 가능하다. 제거하지 않고 횟수를 제한하며, Phase 5(MEAS-03)에서 모의 Discord 서버의 수신 기록을 정답 기준으로 측정한다.
- Discord 웹훅에는 멱등 키가 없다. 서버가 이미 보낸 메시지를 Discord 쪽에서 걸러 줄 방법이 없으므로, 이 한계는 구조적이다.

## 2. 보장하는 것과 보장하지 않는 것

보장하는 것

- 2xx 응답 전에 이벤트와 발송 작업이 DB에 커밋된다 (WEBHOOK-04). 커밋 직후 서버가 죽어도 작업은 남는다.
- 끝나지 않은 작업은 lease가 만료되면 다른 워커(재시작된 서버 포함)가 다시 선점한다.
- 영구 오류(예: 404)와 재시도 소진은 `FAILED`로 끝나며 사유가 기록된다. 소진 사유는 `RETRIES_EXHAUSTED`로 시작한다.
- 같은 delivery id에 대해 작업이 두 개 생기지 않는다.

보장하지 않는 것

- Discord로의 exactly-once 전달. DB 트랜잭션은 외부 API 호출까지 묶을 수 없다.

## 3. 위험 구간

```text
시간 ->
1. 워커가 작업을 선점해 커밋한다        status=PROCESSING, attempts=1, 시도 행 열림(finished_at NULL)
2. 발송 직전에 lease를 갱신한다
3. Discord로 POST 한다
4. Discord가 요청을 받아 메시지를 게시한다
5. SENT를 기록하기 전에 프로세스가 죽는다   <- 위험 구간: Discord에는 있고 DB에는 없다
6. lease가 만료된다
7. 다른 워커가 같은 작업을 선점한다          attempts=2
8. 같은 이슈를 다시 발송한다                  Discord에 두 번째 메시지가 생긴다
9. SENT를 기록한다                            시도 1은 열린 채로, 시도 2는 SUCCESS
```

## 4. 중복이 생기는 조건

1. Discord가 메시지를 받았는데, SENT를 기록하기 전에 프로세스가 죽거나 lease를 잃은 경우.
2. 응답 대기 시간 초과(`READ_TIMEOUT`) 뒤에 재시도하는 경우. 요청이 Discord에 도달했을 수 있다. 5xx 응답도 같은 이유로 결과가 불확실하다.
3. 반대로 중복이 생기지 않는 경우: 연결 실패(`CONNECT_ERROR`)는 요청이 Discord에 닿지 않았음을 알 수 있고, 429와 그 밖의 4xx는 Discord가 거절한 것이므로 재시도해도 메시지가 두 번 게시되지 않는다.

## 5. 재현 방법

실제 프로세스를 강제 종료하는 테스트 두 개가 있다. 둘 다 `@Tag("process")`이며, 앱을 자식 JVM으로 띄우는 `support/AppProcess`를 쓴다 (lease 3초, 같은 Postgres와 WireMock 공유).

`CrashRecoveryProcessIT.kill_after_commit_before_send` : 유실이 없다는 증거

- 워커를 끈 프로세스 A에 서명된 요청을 보내 202를 받는다. 작업은 3초 동안 `PENDING`, attempts 0, Discord 호출 0이다.
- A를 `destroyForcibly`로 종료하고, 워커를 켠 프로세스 B를 같은 DB로 띄운다.
- 결과: 작업은 `SENT`, Discord 수신 1건, attempts 1, 시도 행 1개(SUCCESS). 유실도 중복도 없다.

`CrashRecoveryProcessIT.duplicate_after_received_before_sent` : 중복 구간의 재현

- 모의 Discord는 첫 요청에 3초 지연 후 200을 주고, 이후 요청에는 즉시 200을 준다.
- 프로세스 A가 보낸 요청을 모의 서버가 받은 즉시(작업은 `PROCESSING`, attempts 1) A를 종료한다. 이때 `locked_until - started_at`이 10초 미만임을 확인해 lease 설정이 자식 프로세스에 전달됐음을 검증한다 (기본 30초라면 실패한다).
- 프로세스 B가 lease 만료 후 작업을 다시 선점해 발송한다.
- 결과: 모의 Discord 수신 2건이며 두 요청의 embed url이 같다, 작업은 `SENT`, attempts 2, 시도 1은 `finished_at`과 `outcome`이 NULL, 시도 2는 `SUCCESS`(http_status 200).

결정적으로 같은 로직을 검증하는 프로세스 내 테스트는 `RecoveryIT`다. 프로세스를 죽이는 대신 SQL로 lease를 만료시켜 재선점, 소진, 오래된 결과 무시(펜싱)를 확인한다.

## 6. 완화책과 한계

- lease(기본 30초)는 HTTP read timeout(5초)보다 충분히 길게 유지하고, 워커는 각 발송 직전에 lease를 갱신한다. 응답이 느린 것만으로 작업이 두 번째 워커에게 넘어가지 않는다.
- `attempts`는 선점 횟수다. 서버 종료로 날아간 시도도 예산을 쓴다. 계속 워커를 죽이는 작업은 Discord를 다시 호출하지 않고 `RETRIES_EXHAUSTED`로 `FAILED`가 되므로, 한 작업의 중복 가능 횟수는 `app.worker.retry.max-attempts`(기본 5)로 제한된다.
- 한계: Discord 웹훅은 멱등 키를 받지 않는다. 서버는 이런 중복을 없앨 수 없고 제한하고 측정할 수만 있다.

## 7. 관련 설정과 코드

- 설정: `app.worker.lease-seconds`, `app.worker.retry.*`
- 코드: `JobClaimService`(선점과 소진 판정), `JobResultService`(결과 기록과 lease 갱신)
- Phase 5의 문서화(DOC-01)는 이 파일을 포함하거나 링크한다.
