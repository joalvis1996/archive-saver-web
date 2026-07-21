import os
from cryptography.fernet import Fernet

def get_or_create_encryption_key():
    key = os.getenv("ENCRYPTION_KEY")
    if key:
        return key

    # .env 파일 경로 찾기
    env_path = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), ".env")
    if os.path.exists(env_path):
        with open(env_path, "r", encoding="utf-8") as f:
            lines = f.readlines()
        
        for line in lines:
            if line.strip().startswith("ENCRYPTION_KEY="):
                val = line.split("=", 1)[1].strip().strip('"').strip("'")
                if val:
                    os.environ["ENCRYPTION_KEY"] = val
                    return val

        # .env 파일은 있지만 ENCRYPTION_KEY가 없는 경우 새로 생성 후 추가
        new_key = Fernet.generate_key().decode()
        # 줄바꿈 처리 확인 후 추가
        with open(env_path, "r", encoding="utf-8") as f:
            content = f.read()
        
        with open(env_path, "w", encoding="utf-8") as f:
            f.write(content)
            if not content.endswith("\n"):
                f.write("\n")
            f.write(f'ENCRYPTION_KEY="{new_key}"\n')
            
        os.environ["ENCRYPTION_KEY"] = new_key
        print(f"🔑 새로운 ENCRYPTION_KEY가 생성되어 .env 파일에 저장되었습니다.")
        return new_key
    else:
        # .env 파일 자체가 없는 경우 임시 키 생성 및 환경 변수 등록
        new_key = Fernet.generate_key().decode()
        os.environ["ENCRYPTION_KEY"] = new_key
        return new_key

# 모듈 로드 시 키 확인 및 초기화
ENCRYPTION_KEY = get_or_create_encryption_key()

def get_cipher():
    if not ENCRYPTION_KEY:
        raise ValueError("ENCRYPTION_KEY가 환경 변수 혹은 .env에 존재하지 않습니다.")
    try:
        return Fernet(ENCRYPTION_KEY.encode())
    except Exception as e:
        raise ValueError(f"유효하지 않은 ENCRYPTION_KEY 형식입니다: {str(e)}")

def encrypt_token(token: str) -> str:
    """OAuth 토큰을 AES-128 (Fernet)으로 암호화합니다."""
    if not token:
        return ""
    cipher = get_cipher()
    encrypted = cipher.encrypt(token.encode())
    return encrypted.decode()

def decrypt_token(encrypted_token: str) -> str:
    """암호화된 OAuth 토큰을 복호화하여 평문으로 반환합니다."""
    if not encrypted_token:
        return ""
    cipher = get_cipher()
    decrypted = cipher.decrypt(encrypted_token.encode())
    return decrypted.decode()
