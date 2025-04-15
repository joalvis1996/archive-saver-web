FROM mcr.microsoft.com/playwright/python:v1.51.0-jammy

# 1. Node.js 설치
RUN curl -fsSL https://deb.nodesource.com/setup_18.x | bash - && \
    apt-get install -y nodejs

# 2. 작업 디렉토리
WORKDIR /app

# 3. 프론트엔드 빌드
COPY frontend /app/frontend
WORKDIR /app/frontend
RUN npm install && npm run build

# 4. 백엔드 복사 및 설치
WORKDIR /app
COPY backend /app/backend
COPY backend/requirements.txt /app/backend/requirements.txt
RUN pip install --upgrade pip && pip install -r /app/backend/requirements.txt

# 5. 백엔드 시작 (이게 중요!)
WORKDIR /app/backend
CMD gunicorn app:app --bind 0.0.0.0:$PORT --timeout 120


