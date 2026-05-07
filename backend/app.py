from flask import Flask, request, jsonify, send_from_directory, Response
import dropbox
from bs4 import BeautifulSoup
from urllib.parse import urlparse, urljoin, unquote, parse_qs, quote
import os
import time
import requests
import base64
import json
import re
import mimetypes

app = Flask(__name__, static_folder="../frontend/dist", static_url_path="/_static")

DROPBOX_REFRESH_TOKEN = os.getenv("DROPBOX_REFRESH_TOKEN")
APP_KEY = os.getenv("DROPBOX_APP_KEY")
APP_SECRET = os.getenv("DROPBOX_APP_SECRET")
RAINDROP_ACCESS_TOKEN = os.getenv("RAINDROP_ACCESS_TOKEN")
USE_PLAYWRIGHT_CAPTURE = os.getenv("USE_PLAYWRIGHT_CAPTURE", "true").lower() != "false"
DEFAULT_SHARED_COLLECTION_TITLE = os.getenv("DEFAULT_SHARED_COLLECTION_TITLE", "축구")
CLIPPER_ALLOWED_ORIGINS = {
    "https://archive-saver-web.onrender.com",
    "http://127.0.0.1:5000",
    "http://localhost:5000",
    "https://fmkorea.com",
    "https://www.fmkorea.com",
    "https://m.fmkorea.com",
}

print("=== 환경변수 디버깅 ===")
print("DROPBOX_REFRESH_TOKEN:", "set" if DROPBOX_REFRESH_TOKEN else "missing")
print("APP_KEY:", "set" if APP_KEY else "missing")
print("APP_SECRET:", "set" if APP_SECRET else "missing")
print("RAINDROP_ACCESS_TOKEN:", "set" if RAINDROP_ACCESS_TOKEN else "missing")
print("USE_PLAYWRIGHT_CAPTURE:", USE_PLAYWRIGHT_CAPTURE)
print("=======================")

def is_allowed_cors_origin(origin):
    if not origin:
        return False

    if origin in CLIPPER_ALLOWED_ORIGINS:
        return True

    parsed = urlparse(origin)
    return parsed.scheme == "https" and parsed.netloc.endswith(".fmkorea.com")

@app.after_request
def add_api_cors_headers(response):
    if request.path != "/api/save-html":
        return response

    origin = request.headers.get("Origin")
    if is_allowed_cors_origin(origin):
        response.headers["Access-Control-Allow-Origin"] = origin
        response.headers["Vary"] = "Origin"
        response.headers["Access-Control-Allow-Methods"] = "POST, OPTIONS"
        response.headers["Access-Control-Allow-Headers"] = "Content-Type"

    return response

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

def find_collection_id_by_title(title):
    headers = {"Authorization": f"Bearer {RAINDROP_ACCESS_TOKEN}"}
    res = requests.get("https://api.raindrop.io/rest/v1/collections", headers=headers, timeout=30)
    res.raise_for_status()

    for collection in res.json().get("items", []):
        if collection.get("title") == title:
            return collection.get("_id")

    return None

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

def normalize_media_filename(filename):
    decoded_filename = unquote(filename).strip()

    if (
        not decoded_filename
        or decoded_filename != os.path.basename(decoded_filename)
        or ".." in decoded_filename
    ):
        raise ValueError("Invalid media filename")

    return decoded_filename

def get_archive_media_url(media_type, filename):
    return f"/archive-media/{media_type}/{quote(filename, safe='')}"

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

def is_security_challenge_html(html):
    if not html:
        return False

    security_markers = [
        "에펨코리아 보안 시스템",
        "사람인지 확인이 완료되면",
        "수동 접속 갱신",
        "help@fmkorea.com"
    ]
    return any(marker in html for marker in security_markers)

def security_challenge_response():
    return jsonify({
        "error": (
            "FMKorea가 Render 서버 IP를 보안 확인 페이지로 차단했습니다. "
            "보안 페이지는 아카이브로 저장하지 않았습니다. "
            "이 경우 서버가 대신 접속하는 방식으로는 원문/영상 저장이 어렵고, "
            "휴대폰 브라우저에서 열린 페이지 내용을 직접 보내는 방식이 필요합니다."
        )
    }), 409

def render_page_html_with_playwright(url):
    from playwright.sync_api import sync_playwright

    print("🎭 Playwright 렌더링 캡처 시작:", url)
    with sync_playwright() as p:
        browser = None
        context = None

        try:
            browser = p.chromium.launch(
                headless=True,
                args=["--no-sandbox", "--disable-dev-shm-usage"]
            )
            context = browser.new_context(
                viewport={"width": 390, "height": 1200},
                device_scale_factor=2,
                is_mobile=True,
                has_touch=True,
                locale="ko-KR",
                user_agent=(
                    "Mozilla/5.0 (Linux; Android 14; Mobile) "
                    "AppleWebKit/537.36 (KHTML, like Gecko) "
                    "Chrome/124.0 Mobile Safari/537.36"
                )
            )
            page = context.new_page()
            page.goto(url, wait_until="domcontentloaded", timeout=30000)
            try:
                page.wait_for_load_state("networkidle", timeout=10000)
            except Exception:
                print("⚠️ networkidle 대기 시간 초과, 현재 렌더링 상태로 저장을 계속합니다.")

            page.wait_for_timeout(1500)
            auto_scroll_page(page)
            prepare_lazy_media(page)
            html = page.content()
        finally:
            if context:
                context.close()
            if browser:
                browser.close()

    print("✅ Playwright 렌더링 캡처 완료")
    return html

def auto_scroll_page(page):
    page.evaluate(
        """
        async () => {
          const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
          let previousHeight = 0;

          for (let i = 0; i < 24; i += 1) {
            window.scrollTo(0, document.body.scrollHeight);
            await delay(700);

            const currentHeight = document.body.scrollHeight;
            if (currentHeight === previousHeight) {
              break;
            }
            previousHeight = currentHeight;
          }

          window.scrollTo(0, 0);
          await delay(500);
        }
        """
    )

def prepare_lazy_media(page):
    page.evaluate(
        """
        () => {
          const lazyAttrs = ['data-src', 'data-lazy-src', 'data-original', 'data-url'];

          document.querySelectorAll('img, video, audio, source, iframe').forEach(node => {
            for (const attr of lazyAttrs) {
              const value = node.getAttribute(attr);
              if (value && !node.getAttribute('src')) {
                node.setAttribute('src', value);
              }
            }

            const lazySrcset = node.getAttribute('data-srcset');
            if (lazySrcset && !node.getAttribute('srcset')) {
              node.setAttribute('srcset', lazySrcset);
            }
          });

          document.querySelectorAll('video, audio').forEach(node => {
            node.setAttribute('controls', '');
            node.removeAttribute('autoplay');
          });
        }
        """
    )

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

def download_text_resource(resource_url, base_url):
    try:
        full_url = urljoin(base_url, resource_url)
        response = requests.get(full_url, headers={"Referer": base_url}, timeout=30)
        response.raise_for_status()
        response.encoding = response.encoding or "utf-8"
        return response.text, full_url
    except Exception as e:
        print(f"❌ 텍스트 리소스 다운로드 실패: {resource_url}, {e}")
        return None, None

def inline_css_url_assets(css_text, css_url, page_url):
    def replace_url(match):
        raw_value = match.group(1).strip()
        asset_url = raw_value.strip("\"'")

        if (
            not asset_url
            or asset_url.startswith("data:")
            or asset_url.startswith("#")
            or asset_url.lower().startswith("javascript:")
        ):
            return match.group(0)

        data_uri = download_and_convert_to_base64(asset_url, css_url or page_url)
        if not data_uri:
            return match.group(0)

        return f"url({data_uri})"

    return re.sub(r"url\(([^)]+)\)", replace_url, css_text)

def inline_stylesheets(soup, page_url):
    print("🎨 CSS 인라인 처리 중...")
    for link in list(soup.find_all("link")):
        rel_values = [value.lower() for value in link.get("rel", [])]
        href = link.get("href")

        if "stylesheet" not in rel_values or not href:
            continue

        css_text, css_url = download_text_resource(href, page_url)
        if not css_text:
            link["href"] = urljoin(page_url, href)
            continue

        css_text = inline_css_url_assets(css_text, css_url, page_url)
        style_tag = soup.new_tag("style")
        style_tag.string = css_text
        link.replace_with(style_tag)
        print(f"✅ CSS 인라인 완료: {href}")

def rewrite_dropbox_media_links(html):
    def replace_link(match):
        url = match.group(0)
        parsed_url = urlparse(url)
        filename = os.path.basename(parsed_url.path)
        ext = os.path.splitext(filename)[1].lower()

        if ext in [".mp4", ".webm", ".mov"]:
            return get_archive_media_url("videos", filename)
        if ext in [".mp3", ".ogg", ".wav", ".m4a"]:
            return get_archive_media_url("audio", filename)

        return url

    return re.sub(
        r"https://www\.dropbox\.com/scl/fi/[^\s\"'<>]+",
        replace_link,
        html
    )

def rewrite_archive_media_origin_links(html):
    return re.sub(
        r"https?://[^/\"'<>]+/archive-media/",
        "/archive-media/",
        html
    )

def strip_archive_scripts(soup):
    for script in soup.find_all("script"):
        script.decompose()

    for tag in soup.find_all(True):
        for attr in list(tag.attrs):
            if attr.lower().startswith("on"):
                del tag.attrs[attr]

    for anchor in soup.find_all("a"):
        href = anchor.get("href", "")
        if href.lower().startswith("javascript:"):
            anchor["href"] = "#"

def media_source_type(media_name, src):
    lower_src = src.lower()
    if media_name == "audio":
        if ".ogg" in lower_src:
            return "audio/ogg"
        if ".wav" in lower_src:
            return "audio/wav"
        return "audio/mpeg"

    if ".webm" in lower_src:
        return "video/webm"
    if ".mov" in lower_src:
        return "video/quicktime"
    return "video/mp4"

def insert_native_media_blocks(soup):
    seen_sources = set()

    for media in list(soup.find_all(["video", "audio"])):
        media_src = media.get("src")
        if not media_src:
            first_source = media.find("source")
            media_src = first_source.get("src") if first_source else None

        if not media_src or media_src in seen_sources:
            continue

        seen_sources.add(media_src)

        wrapper = soup.new_tag("div")
        wrapper["class"] = "archive-native-media"

        clean_media = soup.new_tag(media.name)
        clean_media["controls"] = ""
        clean_media["preload"] = "metadata"
        clean_media["playsinline"] = ""
        clean_media["src"] = media_src
        clean_media["style"] = "width:100%;height:auto;background:#000;"

        poster = media.get("poster")
        if poster:
            clean_media["poster"] = poster

        source = soup.new_tag("source", src=media_src)
        source["type"] = media_source_type(media.name, media_src)
        clean_media.append(source)
        wrapper.append(clean_media)

        link = soup.new_tag("a", href=media_src, target="_blank", rel="noopener")
        link["class"] = "archive-media-link"
        link.string = "영상 파일 열기" if media.name == "video" else "오디오 파일 열기"
        wrapper.append(link)

        target = media.find_parent(class_="auto_media_wrapper") or media.find_parent(class_="mejs__container") or media
        target.insert_before(wrapper)

        if target is not media:
            target["class"] = target.get("class", []) + ["archive-original-media-hidden"]
        else:
            media["class"] = media.get("class", []) + ["archive-original-media-hidden"]

def add_archive_media_fallbacks(soup):
    strip_archive_scripts(soup)
    insert_native_media_blocks(soup)

    style = soup.new_tag("style")
    style.string = """
      .archive-original-media-hidden,
      .mejs__container,
      .mejs__controls,
      .mejs__mediaelement,
      .mejs__overlay,
      .mejs__poster,
      .video-poster {
        display: none !important;
      }

      video,
      audio {
        max-width: 100% !important;
        height: auto !important;
        position: relative !important;
        z-index: 2 !important;
        background: #000 !important;
      }

      .archive-media-link {
        display: inline-block;
        margin: 8px 0 16px;
        padding: 8px 10px;
        border-radius: 6px;
        background: #111827;
        color: #fff !important;
        font-size: 14px;
        text-decoration: none;
      }

      .archive-native-media {
        margin: 0 0 16px;
      }
    """

    if soup.head:
        soup.head.append(style)
    else:
        soup.insert(0, style)

    for media in soup.find_all(["video", "audio"]):
        media["controls"] = ""
        media["preload"] = "metadata"
        media["playsinline"] = ""
        media.attrs.pop("autoplay", None)
        media.attrs.pop("data-autoplay", None)

        media_src = media.get("src")
        if media_src and not media.find("source"):
            source = soup.new_tag("source", src=media_src)
            source["type"] = "video/mp4" if media.name == "video" else "audio/mpeg"
            media.append(source)

        for source in media.find_all("source"):
            source_src = source.get("src", "")
            if source_src.endswith(".mp4") or ".mp4?" in source_src:
                source["type"] = "video/mp4"
            elif source_src.endswith(".webm") or ".webm?" in source_src:
                source["type"] = "video/webm"
            elif source_src.endswith(".mp3") or ".mp3?" in source_src:
                source["type"] = "audio/mpeg"
            elif source_src.endswith(".ogg") or ".ogg?" in source_src:
                source["type"] = "audio/ogg"

        link_src = media.get("src")
        if not link_src:
            first_source = media.find("source")
            link_src = first_source.get("src") if first_source else None

        if link_src and not media.find_next_sibling("a", class_="archive-media-link"):
            link = soup.new_tag("a", href=link_src, target="_blank", rel="noopener")
            link["class"] = "archive-media-link"
            link.string = "영상 파일 열기" if media.name == "video" else "오디오 파일 열기"
            media.insert_after(link)

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
            
            # 임시 파일 삭제
            try:
                os.remove(temp_path)
            except:
                pass
            
            return get_archive_media_url(media_type, filename)
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
        collection_title = data.get("collectionTitle") or DEFAULT_SHARED_COLLECTION_TITLE
        client_capture_mode = data.get("clientCaptureMode")

        if not url:
            return jsonify({"error": "Missing URL"}), 400

        if is_security_challenge_html(html):
            print("⚠️ 제공된 HTML이 FMKorea 보안 페이지입니다.")
            return security_challenge_response()

        if not collection_id:
            collection_id = find_collection_id_by_title(collection_title)

        if not collection_id:
            return jsonify({"error": f"'{collection_title}' 컬렉션을 찾지 못했습니다."}), 400

        provided_html = html
        html = provided_html

        if client_capture_mode and not html:
            return jsonify({
                "error": (
                    "앱에서 페이지 HTML을 캡처하지 못했습니다. "
                    "서버 재시도는 FMKorea 보안 페이지로 이어질 수 있어 중단했습니다."
                )
            }), 422

        if not html and USE_PLAYWRIGHT_CAPTURE:
            try:
                captured_html = render_page_html_with_playwright(url)
                if is_security_challenge_html(captured_html):
                    print("⚠️ Playwright 캡처가 FMKorea 보안 페이지로 차단되었습니다.")
                    return security_challenge_response()
                else:
                    html = captured_html
            except Exception as e:
                print(f"⚠️ Playwright 캡처 실패, 기존 HTML/fetch 방식으로 저장합니다: {e}")
                html = provided_html

        if not html:
            print("HTML 본문 없음, 서버에서 페이지를 가져옵니다.")
            html = fetch_page_html(url)

        if is_security_challenge_html(html):
            print("⚠️ FMKorea 보안 페이지 감지: 저장을 중단합니다.")
            return security_challenge_response()

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

        inline_stylesheets(soup, url)

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

        add_archive_media_fallbacks(soup)

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
        html = rewrite_dropbox_media_links(html)
        html = rewrite_archive_media_origin_links(html)
        soup = BeautifulSoup(html, "html.parser")
        add_archive_media_fallbacks(soup)
        html = str(soup)

        return Response(
            html,
            content_type="text/html; charset=utf-8",
            headers={
                "Cache-Control": "private, max-age=300",
                "Content-Security-Policy": (
                    "default-src 'self' data: blob:; "
                    "script-src 'none'; "
                    "style-src 'self' 'unsafe-inline' data: https:; "
                    "img-src 'self' data: blob: https:; "
                    "media-src 'self' data: blob:; "
                    "font-src 'self' data: https:; "
                    "frame-src 'none'; "
                    "object-src 'none'; "
                    "base-uri 'self'"
                ),
                "X-Robots-Tag": "noindex, nofollow"
            }
        )
    except ValueError as e:
        return Response(str(e), status=400, mimetype="text/plain")
    except Exception as e:
        print(f"아카이브 로드 실패: {archive_id}, {e}")
        return Response("Archive not found", status=404, mimetype="text/plain")

@app.route("/archive-media/<media_type>/<path:filename>")
def view_archive_media(media_type, filename):
    try:
        if media_type not in ["videos", "audio", "images", "media"]:
            raise ValueError("Invalid media type")

        safe_filename = normalize_media_filename(filename)
        dropbox_path = f"/web-archives/{media_type}/{safe_filename}"
        _, response = get_dropbox_client().files_download(dropbox_path)
        content = response.content
        content_type = mimetypes.guess_type(safe_filename)[0] or "application/octet-stream"

        range_header = request.headers.get("Range")
        if range_header:
            match = re.match(r"bytes=(\d*)-(\d*)", range_header)
            if match:
                start_text, end_text = match.groups()
                if start_text:
                    start = int(start_text)
                    end = int(end_text) if end_text else len(content) - 1
                else:
                    suffix_length = int(end_text) if end_text else len(content)
                    start = max(len(content) - suffix_length, 0)
                    end = len(content) - 1

                end = min(end, len(content) - 1)

                if start <= end:
                    partial = content[start:end + 1]
                    return Response(
                        partial,
                        status=206,
                        content_type=content_type,
                        headers={
                            "Accept-Ranges": "bytes",
                            "Content-Range": f"bytes {start}-{end}/{len(content)}",
                            "Content-Length": str(len(partial)),
                            "Cache-Control": "private, max-age=86400"
                        }
                    )

                return Response(
                    status=416,
                    headers={
                        "Content-Range": f"bytes */{len(content)}",
                        "Accept-Ranges": "bytes"
                    }
                )

        return Response(
            content,
            content_type=content_type,
            headers={
                "Accept-Ranges": "bytes",
                "Content-Length": str(len(content)),
                "Cache-Control": "private, max-age=86400"
            }
        )
    except ValueError as e:
        return Response(str(e), status=400, mimetype="text/plain")
    except Exception as e:
        print(f"아카이브 미디어 로드 실패: {media_type}/{filename}, {e}")
        return Response("Archive media not found", status=404, mimetype="text/plain")

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
