FROM mcr.microsoft.com/playwright/python:v1.51.0-jammy

# Install Node.js
RUN curl -fsSL https://deb.nodesource.com/setup_18.x | bash - && \
    apt-get install -y nodejs

# Set working directory
WORKDIR /app

# Copy and build frontend
COPY frontend /app/frontend
WORKDIR /app/frontend
RUN npm install && npm run build

# Install backend dependencies
WORKDIR /app
COPY backend /app/backend
COPY backend/requirements.txt /app/backend/requirements.txt
RUN pip install --upgrade pip && pip install -r /app/backend/requirements.txt

# Expose and launch backend
WORKDIR /app/backend
EXPOSE 5000
CMD ["gunicorn", "app:app", "--bind", "0.0.0.0:5000"]
