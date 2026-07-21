import os
import jwt
from functools import wraps
from flask import request, jsonify

# .env 파일 로드 함수
def load_env_file():
    env_path = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), ".env")
    if os.path.exists(env_path):
        with open(env_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                if "=" in line:
                    key, val = line.split("=", 1)
                    key = key.strip()
                    val = val.strip().strip('"').strip("'")
                    if key and not os.getenv(key):
                        os.environ[key] = val

load_env_file()

# Supabase 대시보드 -> Project Settings -> API -> JWT Settings에서 확인 가능한 JWT Secret
SUPABASE_JWT_SECRET = os.getenv("SUPABASE_JWT_SECRET")

def verify_supabase_jwt(token: str) -> dict:
    """
    Supabase JWT 토큰을 검증하고 페이로드를 반환합니다.
    """
    # 1. 먼저 로컬 HS256 검증을 시도합니다. (대칭키 설정이 되어 있고 토큰이 HS256인 경우)
    if SUPABASE_JWT_SECRET:
        try:
            payload = jwt.decode(
                token,
                SUPABASE_JWT_SECRET,
                algorithms=["HS256"],
                options={"verify_aud": True},
                audience="authenticated"
            )
            return payload
        except jwt.exceptions.InvalidAlgorithmError:
            # 알고리즘 불일치 (예: RS256/ES256 사용 시)의 경우 다음 단계로 넘어갑니다.
            pass
        except Exception as e:
            raise e

    # 2. 로컬 검증이 불가능한 경우 (또는 다른 알고리즘인 경우) Supabase API를 통해 토큰을 직접 검증합니다.
    from supabase import create_client
    supabase_url = os.getenv("SUPABASE_URL")
    supabase_key = os.getenv("SUPABASE_SERVICE_ROLE_KEY")
    if not supabase_url or not supabase_key:
        raise ValueError("SUPABASE_URL 또는 SUPABASE_SERVICE_ROLE_KEY가 설정되지 않았습니다.")

    client = create_client(supabase_url, supabase_key)
    try:
        response = client.auth.get_user(jwt=token)
        if response and response.user:
            user = response.user
            return {
                "sub": user.id,
                "email": user.email
            }
        else:
            raise ValueError("유효하지 않은 세션입니다.")
    except Exception as e:
        raise ValueError(f"Supabase Auth API 검증 실패: {str(e)}")

def login_required(f):
    """
    API 요청의 Bearer JWT 토큰을 검사하여 인증되지 않은 사용자를 차단하는 데코레이터입니다.
    성공 시 request.user에 디코딩된 유저 정보(sub, email 등)를 설정합니다.
    """
    @wraps(f)
    def decorated(*args, **kwargs):
        auth_header = request.headers.get("Authorization")
        if not auth_header:
            return jsonify({"error": "인증 헤더(Authorization)가 누락되었습니다."}), 401
        
        parts = auth_header.split()
        if len(parts) != 2 or parts[0].lower() != "bearer":
            return jsonify({"error": "올바른 Bearer 토큰 형식이 아닙니다."}), 401
        
        token = parts[1]
        try:
            user_data = verify_supabase_jwt(token)
            # 유저 ID는 JWT의 'sub' 클레임에 들어있습니다.
            request.user = {
                "id": user_data.get("sub"),
                "email": user_data.get("email")
            }
        except jwt.ExpiredSignatureError:
            return jsonify({"error": "인증 토큰이 만료되었습니다. 다시 로그인해주세요."}), 401
        except jwt.InvalidTokenError as e:
            return jsonify({"error": f"유효하지 않은 인증 토큰입니다: {str(e)}"}), 401
        except Exception as e:
            return jsonify({"error": f"인증 처리 중 오류 발생: {str(e)}"}), 500
        
        return f(*args, **kwargs)
    return decorated
