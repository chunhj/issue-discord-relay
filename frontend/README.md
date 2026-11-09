# 발송 이력 화면

React + TypeScript + Vite로 만든 `/api/history` 조회 화면이다.

- `npm run dev`: 개발 서버를 띄운다. 백엔드가 8080 포트에서 실행 중이어야 하며, `/api` 요청은 Vite 개발 서버가 백엔드로 프록시한다(D-17). 백엔드에 CORS 설정은 필요 없다.
- `npm test`: Vitest와 React Testing Library로 테스트를 실행한다.
- `npm run build`: `dist/`에 정적 산출물을 만든다. `backend/Dockerfile`이 이 빌드 결과를 Spring Boot jar 안에 넣어서 백엔드가 직접 내려 준다(루트 README 참고).
