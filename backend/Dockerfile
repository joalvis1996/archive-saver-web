# Python 3.11 슬림 이미지 사용
FROM python:3.11-slim

# 작업 디렉토리 설정
WORKDIR /app

# 의존성 설치
COPY backend/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 백엔드 코드 복사
COPY backend/ ./

# 앱 실행 (Render에서 자동으로 PORT 지정)
CMD ["gunicorn", "app:app", "--bind", "0.0.0.0:$PORT"]
