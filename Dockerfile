FROM mcr.microsoft.com/playwright/python:v1.42.0-jammy

# 1. Node.js 설치 추가 (v18 기준 예시)
RUN curl -fsSL https://deb.nodesource.com/setup_18.x | bash - && \
    apt-get install -y nodejs

# 2. 작업 디렉토리 설정
WORKDIR /app

# 3. frontend 복사 및 빌드
COPY frontend /app/frontend
WORKDIR /app/frontend
RUN npm install && npm run build

# 4. backend 설정
WORKDIR /app
COPY backend /app/backend
COPY backend/requirements.txt /app/backend/requirements.txt
RUN pip install --upgrade pip && pip install -r /app/backend/requirements.txt

# 5. 포트 노출 및 실행
WORKDIR /app/backend
EXPOSE 5000
CMD ["gunicorn", "--chdir", "backend", "app:app", "--bind", "0.0.0.0:5000"]
