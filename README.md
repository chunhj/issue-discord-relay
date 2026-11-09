# issue-discord-relay

[![CI](https://github.com/chunhj/issue-discord-relay/actions/workflows/ci.yml/badge.svg)](https://github.com/chunhj/issue-discord-relay/actions/workflows/ci.yml)

GitHub 저장소에 새 이슈가 생기면 Discord 채널에 이슈 제목과 링크를 보내 주는 서비스다.
GitHub가 보낸 요청의 서명을 확인하고, 발송 작업을 DB에 저장하고, 실패하면 다시 시도한다.
받은 기록과 보낸 기록은 화면 한 곳에서 본다.

## 핵심 약속

- GitHub에 2xx 응답을 돌려준 이벤트는 이미 DB에 저장된 상태다.
- 그래서 서버가 죽거나 Discord가 불안정해도 유실되지 않고 전달된다.
- 전달은 at-least-once다. Discord 웹훅에는 멱등 키가 없어서 같은 메시지가 두 번 갈 수 있다.
- 중복은 없다고 말하지 않는다. 얼마나 생기는지 측정해서 보고한다. 측정 숫자는 이후 단계에서 채운다.

자세한 설명은 [docs/delivery-semantics.md](docs/delivery-semantics.md)에 있다.

## 구성

```text
GitHub  ──> /webhooks/github ──┐
                               ├─> app (Spring Boot) ──> postgres
브라우저 ──> 화면, /api/history ─┘        └──> Discord
```

- 컨테이너는 둘이다. app(Spring Boot)과 postgres.
- app이 빌드된 화면(React)도 직접 내려 준다. 따로 웹 서버를 두지 않았다.
- 바깥에 열려 있는 것은 app 포트(80 -> 8080)뿐이다. DB 포트는 `127.0.0.1`에만 열린다.

## 로컬에서 실행

Docker가 필요하다.

1. `.env.example`을 `.env`로 복사하고 값을 채운다.
2. 실행한다.

   ```bash
   docker compose up -d --build
   ```

3. 브라우저에서 http://localhost 를 연다. `HTTP_PORT`를 바꿨다면 그 포트로 연다.
4. 경계 확인 스크립트를 돌린다.

   ```bash
   scripts/smoke.sh http://localhost
   ```

참고

- 80번이나 5432번 포트를 다른 프로그램이 쓰고 있으면 `.env`에 `HTTP_PORT`, `DB_HOST_PORT`를 적어서 바꾼다.
- Windows의 `SPRING_DATASOURCE_PASSWORD` 환경변수는 백엔드를 호스트에서 직접 띄울 때만 영향을 준다. 컨테이너에는 전달되지 않는다.
- 멈출 때는 `docker compose down`을 쓴다. `docker compose down -v`는 DB 볼륨까지 지우니 데이터가 사라진다.
- `.env`가 없으면 `docker compose` 명령이 모두 실패한다. 필수 변수를 파일 전체에서 검사하기 때문이다.

## 서버(EC2)에 배포

서버에서 이미지를 직접 빌드하는 방식이다.

1. 서버에서 저장소를 한 번 `git clone` 한다.
2. 서버에만 `.env`를 만든다. 커밋하지 않고, 채팅이나 이슈에도 붙여 넣지 않는다.
3. DB 비밀번호(`DB_PASSWORD`)는 첫 실행 전에 정한다. Postgres는 처음 정한 비밀번호를 계속 쓰기 때문에 나중에 `.env`만 바꾸면 백엔드가 접속하지 못한다.
4. 처음에는 `docker compose up -d --build`를 실행한다.
5. 업데이트할 때는 아래를 실행한다. 마지막 줄은 안 쓰는 옛 이미지를 지워서 디스크(8GB)가 차지 않게 한다.

   ```bash
   git pull
   docker compose up -d --build
   docker image prune -f
   ```

- 80번 포트를 연다. 5432와 8080은 열지 않는다.
- SSH(22번)는 키로만 로그인하게 하고, 가능하면 자기 IP로 제한한다.
- GitHub 저장소의 Webhook은 `http://<서버 주소>/webhooks/github`로 보내고, Secret은 서버 `.env`의 `GITHUB_WEBHOOK_SECRET`과 같게 둔다.

## 테스트와 CI

- 백엔드: `backend/gradlew -p backend test` (Testcontainers를 쓰므로 Docker가 필요하다)
- 프론트엔드: `npm --prefix frontend run lint`, `npm --prefix frontend run test`, `npm --prefix frontend run build`

GitHub Actions가 push와 pull request마다 같은 검사를 돌리고, 이미지 빌드까지 확인한다.
위의 배지가 최근 실행 결과를 보여 준다.

## 보안 메모

- `/webhooks/github`는 백엔드가 HMAC 서명을 직접 검사한다. 서명이 틀리면 401이다.
- DB 포트는 바깥에 공개하지 않는다.
- 비밀 값(웹훅 시크릿, Discord URL, DB 비밀번호)은 서버의 `.env`에만 둔다. 저장소에는 가짜 값이 든 `.env.example`만 있다.

## 백업(선택)

예시다. 저장소 바깥 경로에 gzip 파일로 덤프한다.

```bash
docker compose exec -T postgres pg_dump -U webhook webhook | gzip > ~/backup-$(date +%F).sql.gz
```

## 알려진 한계

- at-least-once라서 중복 발송이 생길 수 있다.
- 서버 한 대로 운영한다. 이중화와 DB 복제는 없다.
- 모니터링 시스템은 따로 두지 않았다. 컨테이너 로그로 확인한다.
