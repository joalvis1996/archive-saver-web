# python image 사용
FROM python:3.11-slim

# 작업 디렉토리 설정
WORKDIR /app

# requirements.txt 복사
COPY backend/requirements.txt .

# gunicorn 설치
RUN pip install -r requirements.txt

# 백엔드 앱 실행
COPY backend/ ./
CMD ["gunicorn", "app:app"]
