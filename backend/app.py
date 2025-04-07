# backend/app.py
from flask import Flask, request, jsonify, send_from_directory
import requests
import dropbox
from bs4 import BeautifulSoup
from urllib.parse import urlparse, urljoin
import os

app = Flask(__name__, static_folder="../frontend/dist", static_url_path="/")

DROPBOX_REFRESH_TOKEN = os.getenv("DROPBOX_REFRESH_TOKEN")
APP_KEY = os.getenv("DROPBOX_APP_KEY")
APP_SECRET = os.getenv("DROPBOX_APP_SECRET")
RAINDROP_ACCESS_TOKEN = os.getenv("RAINDROP_ACCESS_TOKEN")

print(f"DROPBOX_APP_KEY: {os.getenv('DROPBOX_APP_KEY')}")
print(f"RAINDROP_ACCESS_TOKEN: {os.getenv('RAINDROP_ACCESS_TOKEN')}")


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

def get_dropbox_client():
    return dropbox.Dropbox(
        app_key=APP_KEY,
        app_secret=APP_SECRET,
        oauth2_refresh_token=DROPBOX_REFRESH_TOKEN
    )

@app.route("/api/collections", methods=["GET"])
def get_collections():
    try:
        headers = {"Authorization": f"Bearer {RAINDROP_ACCESS_TOKEN}"}
        res = requests.get("https://api.raindrop.io/rest/v1/collections", headers=headers)
        return jsonify(res.json().get("items", []))
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route("/api/save", methods=["POST"])
def save_page():
    data = request.json
    url = data.get("url")
    collection_id = data.get("collectionId")

    if not url or not collection_id:
        return jsonify({"error": "Missing url or collectionId"}), 400

    try:
        res = requests.get(url)
        soup = BeautifulSoup(res.text, "html.parser")

        for tag, attr in {"img": "src", "script": "src", "link": "href"}.items():
            for node in soup.find_all(tag):
                if node.has_attr(attr):
                    node[attr] = urljoin(url, node[attr])

        parsed = urlparse(url)
        filename = parsed.netloc + parsed.path.replace("/", "_") + ".html"
        filepath = f"/tmp/{filename}"

        with open(filepath, "w", encoding="utf-8") as f:
            f.write(str(soup))

        dbx = get_dropbox_client()
        dropbox_path = f"/web-archives/{filename}"
        with open(filepath, "rb") as f:
            dbx.files_upload(f.read(), dropbox_path, mode=dropbox.files.WriteMode.overwrite)

        shared_url = dbx.sharing_create_shared_link_with_settings(dropbox_path).url
        shared_url = shared_url.replace("?dl=0", "?raw=1")

        title = soup.title.string.strip() if soup.title else "Untitled"
        domain_tag = parsed.netloc
        cover_image_url = extract_cover_image(soup, url)

        headers = {
            "Authorization": f"Bearer {RAINDROP_ACCESS_TOKEN}",
            "Content-Type": "application/json"
        }
        payload = {
            "link": shared_url,
            "title": title,
            "excerpt": url,
            "tags": [domain_tag],
            "collection": {"$id": collection_id}
        }
        if cover_image_url:
            payload["cover"] = cover_image_url

        r = requests.post("https://api.raindrop.io/rest/v1/raindrop", headers=headers, json=payload)
        if r.status_code == 200:
            return jsonify({"message": "Raindrop 저장 완료!"})
        else:
            return jsonify({"error": f"Raindrop 저장 실패: {r.status_code}"}), 500

    except Exception as e:
        return jsonify({"error": str(e)}), 500

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
    app.run(debug=True, port=5000)