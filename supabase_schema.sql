-- Supabase SQL Schema for Archive Saver SaaS

-- 1. Profiles 테이블 생성 (auth.users와 자동으로 동기화됨)
CREATE TABLE public.profiles (
    id UUID REFERENCES auth.users ON DELETE CASCADE PRIMARY KEY,
    email TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT TIMEZONE('utc'::text, NOW()) NOT NULL
);

-- Profiles 테이블 Row-Level Security (RLS) 활성화
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;

CREATE POLICY "유저는 자신의 프로필만 볼 수 있습니다." 
    ON public.profiles FOR SELECT 
    USING (auth.uid() = id);

-- 회원가입 시 auth.users의 정보를 public.profiles 테이블로 자동 복사하는 트리거 함수
CREATE OR REPLACE FUNCTION public.handle_new_user()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO public.profiles (id, email)
    VALUES (new.id, new.email);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 회원가입 완료 시 동작할 트리거 생성
CREATE OR REPLACE TRIGGER on_auth_user_created
    AFTER INSERT ON auth.users
    FOR EACH ROW EXECUTE FUNCTION public.handle_new_user();


-- 2. User Storage Tokens 테이블 생성 (유저별 클라우드 스토리지 OAuth 리프레시 토큰 저장)
CREATE TABLE public.user_storage_tokens (
    user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE PRIMARY KEY,
    provider TEXT NOT NULL CHECK (provider IN ('dropbox', 'google')),
    encrypted_token TEXT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT TIMEZONE('utc'::text, NOW()) NOT NULL
);

-- User Storage Tokens RLS 활성화
ALTER TABLE public.user_storage_tokens ENABLE ROW LEVEL SECURITY;

CREATE POLICY "유저는 자신의 스토리지 토큰만 관리할 수 있습니다."
    ON public.user_storage_tokens FOR ALL
    USING (auth.uid() = user_id);


-- 3. Archives 테이블 생성 (유저별 아카이브 내역 메타데이터 저장)
CREATE TABLE public.archives (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE NOT NULL,
    url TEXT NOT NULL,
    title TEXT NOT NULL,
    storage_provider TEXT NOT NULL CHECK (storage_provider IN ('dropbox', 'google')),
    storage_shared_link TEXT,
    storage_file_id TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT TIMEZONE('utc'::text, NOW()) NOT NULL
);

-- Archives RLS 활성화
ALTER TABLE public.archives ENABLE ROW LEVEL SECURITY;

CREATE POLICY "유저는 자신의 아카이브 내역만 조회할 수 있습니다."
    ON public.archives FOR SELECT
    USING (auth.uid() = user_id);

CREATE POLICY "유저는 자신의 아카이브 내역만 추가할 수 있습니다."
    ON public.archives FOR INSERT
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "유저는 자신의 아카이브 내역만 삭제할 수 있습니다."
    ON public.archives FOR DELETE
    USING (auth.uid() = user_id);
