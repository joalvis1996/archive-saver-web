# 1. 베이스 이미지
FROM mcr.microsoft.com/playwright/python:v1.42.0-jammy

# 2. 작업 디렉토리 설정
WORKDIR /app

# 3. frontend 빌드
COPY frontend /app/frontend
WORKDIR /app/frontend
RUN npm install && npm run build

# 4. backend 설정
WORKDIR /app
COPY backend /app/backend
COPY backend/requirements.txt /app/backend/requirements.txt
RUN pip install --upgrade pip && pip install -r /app/backend/requirements.txt

# 5. Playwright 브라우저 설치
RUN playwright install chromium

# 6. 포트 설정 (Flask + Gunicorn)
EXPOSE 5000

# 7. 실행 명령
CMD ["gunicorn", "--chdir", "backend", "app:app", "--bind", "0.0.0.0:5000"]
