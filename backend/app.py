from flask import Flask, request, jsonify, send_from_directory, Response
import dropbox
from bs4 import BeautifulSoup
from urllib.parse import urlparse, urljoin, unquote, parse_qs, quote
import os
import time
import requests
import base64
import json

app = Flask(__name__, static_folder="../frontend/dist", static_url_path="/_static")

DROPBOX_REFRESH_TOKEN = os.getenv("DROPBOX_REFRESH_TOKEN")
APP_KEY = os.getenv("DROPBOX_APP_KEY")
APP_SECRET = os.getenv("DROPBOX_APP_SECRET")
RAINDROP_ACCESS_TOKEN = os.getenv("RAINDROP_ACCESS_TOKEN")

print("=== 환경변수 디버깅 ===")
print("DROPBOX_REFRESH_TOKEN:", "set" if DROPBOX_REFRESH_TOKEN else "missing")
print("APP_KEY:", "set" if APP_KEY else "missing")
print("APP_SECRET:", "set" if APP_SECRET else "missing")
print("RAINDROP_ACCESS_TOKEN:", "set" if RAINDROP_ACCESS_TOKEN else "missing")
print("=======================")

def extract_cover_image(soup, base_url):
    og = soup.find("meta", property="og:image")
    if og and og.get("content"):
        return urljoin(base_url, og["content"])
    tw = soup.find("meta", attrs={"name": "twitter:image"})
    if tw and tw.get("content"):
        return urljoin(base_url, tw["content"])
    link = soup.find("link", rel="image_src")
    if link and link.get("href"):
        return urljoin(base_url, link["href"])
    return None

def get_request_origin():
    scheme = request.headers.get("X-Forwarded-Proto", request.scheme).split(",")[0].strip()
    host = request.headers.get("X-Forwarded-Host", request.host).split(",")[0].strip()
    return f"{scheme}://{host}"

def get_dropbox_client():
    return dropbox.Dropbox(
        app_key=APP_KEY,
        app_secret=APP_SECRET,
        oauth2_refresh_token=DROPBOX_REFRESH_TOKEN
    )

def get_shared_link(dropbox_path, use_raw=False):
    """
    Dropbox 파일의 공유 링크를 생성하거나 가져옵니다.
    
    Args:
        dropbox_path: Dropbox 파일 경로
        use_raw: True이면 ?raw=1 파라미터를 추가 (미디어 파일용), False이면 일반 공유 링크 (HTML 파일용)
    
    Returns:
        공유 링크 URL
    """
    dbx = get_dropbox_client()
    links = dbx.sharing_list_shared_links(path=dropbox_path).links
    if links:
        # 기존 링크가 있으면 사용
        base_url = links[0].url.split("?")[0]  # 쿼리 파라미터 제거
        if use_raw:
            return f"{base_url}?raw=1"
        else:
            return base_url
    # 새 공유 링크 생성
    settings = dropbox.sharing.SharedLinkSettings(requested_visibility=dropbox.sharing.RequestedVisibility.public)
    base_url = dbx.sharing_create_shared_link_with_settings(dropbox_path, settings).url.split("?")[0]
    if use_raw:
        return f"{base_url}?raw=1"
    else:
        return base_url

def generate_filename(parsed):
    query = parse_qs(parsed.query)
    doc_id = query.get("document_srl", [""])[0]
    if doc_id:
        return f"{parsed.netloc}_{doc_id}.html"
    else:
        last_segment = parsed.path.strip("/").replace("/", "_") or "index"
        return f"{parsed.netloc}_{last_segment}.html"

def get_archive_id(filename):
    return filename[:-5] if filename.endswith(".html") else filename

def get_archive_url(filename):
    archive_id = quote(get_archive_id(filename), safe="")
    return f"{get_request_origin()}/archive/{archive_id}"

def normalize_archive_filename(archive_id):
    decoded_id = unquote(archive_id).strip()
    filename = decoded_id if decoded_id.endswith(".html") else f"{decoded_id}.html"

    if not filename or filename != os.path.basename(filename) or ".." in filename:
        raise ValueError("Invalid archive id")

    return filename

def fetch_page_html(url):
    headers = {
        "User-Agent": (
            "Mozilla/5.0 (Linux; Android 14; Mobile) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/124.0 Safari/537.36"
        ),
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
    }
    response = requests.get(url, headers=headers, timeout=30)
    response.raise_for_status()
    return response.text

def download_and_convert_to_base64(media_url, base_url):
    """
    미디어 파일을 다운로드하고 base64로 인코딩하여 data URI를 반환합니다.
    HTML에 직접 포함시켜 외부 링크 의존성을 제거합니다.
    
    Args:
        media_url: 미디어 파일의 URL (상대 또는 절대)
        base_url: 기본 URL (상대 URL을 절대 URL로 변환하기 위함)
    
    Returns:
        data URI 문자열 (예: "data:image/gif;base64,...") 또는 None (실패 시)
    """
    try:
        full_url = urljoin(base_url, media_url)
        parsed_url = urlparse(full_url)
        
        # 파일 다운로드
        response = requests.get(full_url, headers={"Referer": base_url}, timeout=30, stream=True)
        response.raise_for_status()
        
        # 파일 내용 읽기
        content = b""
        for chunk in response.iter_content(chunk_size=8192):
            content += chunk
        
        # MIME 타입 결정
        content_type = response.headers.get('Content-Type', '')
        if not content_type:
            # 확장자로 MIME 타입 추정
            ext = os.path.splitext(parsed_url.path)[1].lower()
            mime_types = {
                '.gif': 'image/gif',
                '.jpg': 'image/jpeg',
                '.jpeg': 'image/jpeg',
                '.png': 'image/png',
                '.webp': 'image/webp',
                '.mp4': 'video/mp4',
                '.webm': 'video/webm',
                '.mp3': 'audio/mpeg',
                '.ogg': 'audio/ogg',
            }
            content_type = mime_types.get(ext, 'application/octet-stream')
        
        # base64 인코딩
        base64_data = base64.b64encode(content).decode('utf-8')
        data_uri = f"data:{content_type};base64,{base64_data}"
        
        return data_uri
    except Exception as e:
        print(f"❌ 미디어 다운로드 및 변환 실패: {media_url}, {e}")
        return None

def download_and_save_media(media_url, base_url, media_type="media", use_base64=True):
    """
    미디어 파일을 다운로드하고 처리합니다.
    
    Args:
        media_url: 미디어 파일의 URL (상대 또는 절대)
        base_url: 기본 URL (상대 URL을 절대 URL로 변환하기 위함)
        media_type: 미디어 타입 ("videos", "images", "audio" 등)
        use_base64: True이면 base64로 인코딩하여 반환, False이면 Dropbox 링크 반환
    
    Returns:
        data URI 또는 공유 링크 URL 또는 None (실패 시)
    """
    if use_base64:
        # base64로 인코딩하여 반환 (HTML에 직접 포함)
        return download_and_convert_to_base64(media_url, base_url)
    else:
        # 기존 방식: Dropbox에 저장하고 링크 반환
        try:
            full_url = urljoin(base_url, media_url)
            parsed_url = urlparse(full_url)
            
            # 파일명 추출
            filename = os.path.basename(parsed_url.path)
            if not filename or '.' not in filename:
                filename = f"media_{hash(full_url) % 100000}.{parsed_url.path.split('.')[-1] if '.' in parsed_url.path else 'bin'}"
            
            # 파일 다운로드
            response = requests.get(full_url, headers={"Referer": base_url}, timeout=30, stream=True)
            response.raise_for_status()
            
            # 임시 파일로 저장
            temp_path = f"/tmp/{filename}"
            with open(temp_path, "wb") as f:
                for chunk in response.iter_content(chunk_size=8192):
                    f.write(chunk)
            
            # Dropbox에 업로드
            dropbox_path = f"/web-archives/{media_type}/{filename}"
            with open(temp_path, "rb") as f:
                get_dropbox_client().files_upload(
                    f.read(), 
                    dropbox_path, 
                    mode=dropbox.files.WriteMode.overwrite
                )
            
            # 공유 링크 생성 (미디어 파일이므로 raw=1 사용)
            shared_link = get_shared_link(dropbox_path, use_raw=True)
            
            # 임시 파일 삭제
            try:
                os.remove(temp_path)
            except:
                pass
            
            return shared_link
        except Exception as e:
            print(f"❌ 미디어 다운로드 실패 ({media_type}): {media_url}, {e}")
            return None


@app.route("/api/save-html", methods=["POST"])
def save_html_direct():
    try:
        print("=== /api/save-html 호출됨 ===")
        data = request.json
        url = data.get("url")
        html = data.get("html")
        collection_id = data.get("collectionId")

        if not url or not collection_id:
            return jsonify({"error": "Missing fields"}), 400

        if not html:
            print("HTML 본문 없음, 서버에서 페이지를 가져옵니다.")
            html = fetch_page_html(url)

        parsed = urlparse(url)
        filename = generate_filename(parsed)
        filepath = f"/tmp/{filename}"

        soup = BeautifulSoup(html, "html.parser")

        # 상대 URL을 절대 URL로 변환
        for tag, attr in {
            "img": "src",
            "script": "src",
            "link": "href",
            "source": "src",
            "video": "src",
            "audio": "src",
            "iframe": "src"
        }.items():
            for node in soup.find_all(tag):
                if node.has_attr(attr):
                    node[attr] = urljoin(url, node[attr])

        # 모든 이미지 다운로드 및 저장 (GIF 포함)
        print("🖼️ 이미지 처리 중...")
        for img in soup.find_all("img"):
            # src 속성 처리
            img_src = img.get("src")
            if img_src:
                # GIF 파일인 경우 images 폴더에 저장
                img_src_lower = img_src.lower()
                is_gif = img_src_lower.endswith(".gif") or "gif" in img_src_lower
                media_type = "images"
                
                shared_link = download_and_save_media(img_src, url, media_type)
                if shared_link:
                    img["src"] = shared_link
                    print(f"✅ 이미지 저장 완료: {img_src}")
            
            # 지연 로딩 속성 처리 (data-src, data-lazy-src 등)
            for lazy_attr in ["data-src", "data-lazy-src", "data-original", "data-url"]:
                lazy_src = img.get(lazy_attr)
                if lazy_src:
                    # 절대 URL로 변환
                    full_lazy_url = urljoin(url, lazy_src)
                    lazy_src_lower = full_lazy_url.lower()
                    is_gif = lazy_src_lower.endswith(".gif") or "gif" in lazy_src_lower
                    media_type = "images"
                    
                    shared_link = download_and_save_media(lazy_src, url, media_type)
                    if shared_link:
                        img[lazy_attr] = shared_link
                        # 지연 로딩 속성이 있고 src가 없으면 src에도 설정
                        if not img.get("src"):
                            img["src"] = shared_link
                        print(f"✅ 지연 로딩 이미지 저장 완료 ({lazy_attr}): {lazy_src}")
            
            # img 태그의 srcset 속성 처리 (반응형 이미지)
            img_srcset = img.get("srcset")
            if img_srcset:
                srcset_parts = []
                for part in img_srcset.split(","):
                    part = part.strip()
                    if not part:
                        continue
                    # URL과 descriptor 분리
                    parts = part.split()
                    if parts:
                        image_url = parts[0]
                        descriptor = " ".join(parts[1:]) if len(parts) > 1 else ""
                        
                        shared_link = download_and_save_media(image_url, url, "images")
                        if shared_link:
                            if descriptor:
                                srcset_parts.append(f"{shared_link} {descriptor}")
                            else:
                                srcset_parts.append(shared_link)
                            print(f"✅ img srcset 이미지 저장 완료: {image_url}")
                        else:
                            # 실패한 경우 원본 유지
                            srcset_parts.append(part)
                
                if srcset_parts:
                    img["srcset"] = ", ".join(srcset_parts)

        # video 태그의 src 속성 처리
        print("🎥 video 태그 처리 중...")
        for video in soup.find_all("video"):
            # src 속성 처리
            video_src = video.get("src")
            if video_src:
                # 비디오는 파일 크기가 크므로 Dropbox 링크 사용 (base64 사용 안 함)
                shared_link = download_and_save_media(video_src, url, "videos", use_base64=False)
                if shared_link:
                    video["src"] = shared_link
                    print(f"✅ 비디오 저장 완료: {video_src}")
            
            # 지연 로딩 속성 처리 (data-src 등)
            for lazy_attr in ["data-src", "data-lazy-src", "data-original", "data-url"]:
                lazy_src = video.get(lazy_attr)
                if lazy_src:
                    # 비디오는 파일 크기가 크므로 Dropbox 링크 사용
                    shared_link = download_and_save_media(lazy_src, url, "videos", use_base64=False)
                    if shared_link:
                        video[lazy_attr] = shared_link
                        if not video.get("src"):
                            video["src"] = shared_link
                        print(f"✅ 지연 로딩 비디오 저장 완료 ({lazy_attr}): {lazy_src}")
            
            # poster 속성 처리 (비디오 썸네일) - 이미지이므로 base64 사용
            poster_src = video.get("poster")
            if poster_src:
                shared_link = download_and_save_media(poster_src, url, "images", use_base64=True)
                if shared_link:
                    video["poster"] = shared_link
                    print(f"✅ 비디오 포스터 저장 완료: {poster_src}")

        # video 태그 내부의 모든 source 태그 처리
        print("🎬 video > source 태그 처리 중...")
        for video in soup.find_all("video"):
            for source in video.find_all("source"):
                # src 속성 처리
                source_src = source.get("src")
                if source_src:
                    # 비디오는 파일 크기가 크므로 Dropbox 링크 사용
                    shared_link = download_and_save_media(source_src, url, "videos", use_base64=False)
                    if shared_link:
                        source["src"] = shared_link
                        print(f"✅ 비디오 소스 저장 완료: {source_src}")
                
                # 지연 로딩 속성 처리
                for lazy_attr in ["data-src", "data-lazy-src", "data-original", "data-url"]:
                    lazy_src = source.get(lazy_attr)
                    if lazy_src:
                        # 비디오는 파일 크기가 크므로 Dropbox 링크 사용
                        shared_link = download_and_save_media(lazy_src, url, "videos", use_base64=False)
                        if shared_link:
                            source[lazy_attr] = shared_link
                            if not source.get("src"):
                                source["src"] = shared_link
                            print(f"✅ 지연 로딩 비디오 소스 저장 완료 ({lazy_attr}): {lazy_src}")

        # audio 태그 처리
        print("🔊 audio 태그 처리 중...")
        for audio in soup.find_all("audio"):
            audio_src = audio.get("src")
            if audio_src:
                # 오디오는 파일 크기가 클 수 있으므로 Dropbox 링크 사용
                shared_link = download_and_save_media(audio_src, url, "audio", use_base64=False)
                if shared_link:
                    audio["src"] = shared_link
                    print(f"✅ 오디오 저장 완료: {audio_src}")

        # audio > source 태그 처리
        for audio in soup.find_all("audio"):
            for source in audio.find_all("source"):
                source_src = source.get("src")
                if source_src:
                    # 오디오는 파일 크기가 클 수 있으므로 Dropbox 링크 사용
                    shared_link = download_and_save_media(source_src, url, "audio", use_base64=False)
                    if shared_link:
                        source["src"] = shared_link
                        print(f"✅ 오디오 소스 저장 완료: {source_src}")

        # picture > source 태그 처리 (반응형 이미지)
        print("🖼️ picture > source 태그 처리 중...")
        for picture in soup.find_all("picture"):
            for source in picture.find_all("source"):
                source_srcset = source.get("srcset")
                if source_srcset:
                    # srcset은 여러 이미지를 포함할 수 있음 (예: "image1.jpg 1x, image2.jpg 2x")
                    # 모든 이미지를 처리
                    srcset_parts = []
                    for part in source_srcset.split(","):
                        part = part.strip()
                        if not part:
                            continue
                        # URL과 descriptor 분리 (예: "image.jpg 1x" -> ["image.jpg", "1x"])
                        parts = part.split()
                        if parts:
                            image_url = parts[0]
                            descriptor = " ".join(parts[1:]) if len(parts) > 1 else ""
                            
                            shared_link = download_and_save_media(image_url, url, "images")
                            if shared_link:
                                if descriptor:
                                    srcset_parts.append(f"{shared_link} {descriptor}")
                                else:
                                    srcset_parts.append(shared_link)
                                print(f"✅ picture 소스 저장 완료: {image_url}")
                            else:
                                # 실패한 경우 원본 유지
                                srcset_parts.append(part)
                    
                    if srcset_parts:
                        source["srcset"] = ", ".join(srcset_parts)

        with open(filepath, "w", encoding="utf-8") as f:
            f.write(str(soup))

        dbx = get_dropbox_client()
        dropbox_path = f"/web-archives/{filename}"
        with open(filepath, "rb") as f:
            dbx.files_upload(f.read(), dropbox_path, mode=dropbox.files.WriteMode.overwrite)

        archive_url = get_archive_url(filename)
        # Dropbox 링크는 백업용으로 유지하고, Raindrop에는 Archive Saver 뷰어 URL을 저장합니다.
        shared_url = get_shared_link(dropbox_path, use_raw=False)
        title = soup.title.string.strip() if soup.title else "Untitled"
        domain_tag = parsed.netloc
        cover_image_url = extract_cover_image(soup, url)

        raindrop_headers = {
            "Authorization": f"Bearer {RAINDROP_ACCESS_TOKEN}",
            "Content-Type": "application/json"
        }
        payload = {
            "link": archive_url,
            "title": title,
            "excerpt": url,
            "note": f"Original URL: {url}\nDropbox backup: {shared_url}",
            "tags": [domain_tag],
            "collection": {"$id": collection_id}
        }
        if cover_image_url:
            payload["cover"] = cover_image_url

        r = requests.post("https://api.raindrop.io/rest/v1/raindrop", headers=raindrop_headers, json=payload)
        if r.status_code == 200:
            return jsonify({
                "message": "저장 완료!",
                "archiveUrl": archive_url
            })
        else:
            return jsonify({"error": f"Raindrop 저장 실패: {r.status_code}"}), 500

    except Exception as e:
        print("예외 발생:", str(e))
        return jsonify({"error": str(e)}), 500

@app.route("/api/collections", methods=["GET"])
def get_collections():
    try:
        headers = {"Authorization": f"Bearer {RAINDROP_ACCESS_TOKEN}"}
        res = requests.get("https://api.raindrop.io/rest/v1/collections", headers=headers)
        res.raise_for_status()
        return jsonify(res.json().get("items", []))
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route("/manifest.webmanifest")
def manifest():
    origin = get_request_origin()
    payload = {
        "name": "Archive Saver",
        "short_name": "Archive",
        "description": "Save shared web pages to Raindrop through Archive Saver.",
        "start_url": "/",
        "scope": "/",
        "display": "standalone",
        "background_color": "#111827",
        "theme_color": "#111827",
        "icons": [
            {
                "src": "/archive-icon-192.png",
                "sizes": "192x192",
                "type": "image/png",
                "purpose": "any"
            },
            {
                "src": "/archive-icon-512.png",
                "sizes": "512x512",
                "type": "image/png",
                "purpose": "any maskable"
            },
            {
                "src": "/archive-icon.svg",
                "sizes": "any",
                "type": "image/svg+xml",
                "purpose": "any maskable"
            }
        ],
        "share_target": {
            "action": f"{origin}/share",
            "method": "GET",
            "params": {
                "title": "title",
                "text": "text",
                "url": "url"
            }
        }
    }
    return Response(
        json.dumps(payload, ensure_ascii=False),
        mimetype="application/manifest+json"
    )

@app.route("/archive/<path:archive_id>")
def view_archive(archive_id):
    try:
        filename = normalize_archive_filename(archive_id)
        dropbox_path = f"/web-archives/{filename}"
        _, response = get_dropbox_client().files_download(dropbox_path)
        html = response.content.decode("utf-8", errors="replace")

        return Response(
            html,
            content_type="text/html; charset=utf-8",
            headers={
                "Cache-Control": "private, max-age=300",
                "X-Robots-Tag": "noindex, nofollow"
            }
        )
    except ValueError as e:
        return Response(str(e), status=400, mimetype="text/plain")
    except Exception as e:
        print(f"아카이브 로드 실패: {archive_id}, {e}")
        return Response("Archive not found", status=404, mimetype="text/plain")

@app.route("/")
def index():
    return send_from_directory(app.static_folder, "index.html")

@app.route("/<path:path>")
def serve_static(path):
    file_path = os.path.join(app.static_folder, path)
    if os.path.exists(file_path):
        return send_from_directory(app.static_folder, path)
    return send_from_directory(app.static_folder, "index.html")

if __name__ == "__main__":
    app.run(debug=True, host="0.0.0.0", port=5000)
