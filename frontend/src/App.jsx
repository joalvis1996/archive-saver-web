// src/App.jsx
import React, { useCallback, useEffect, useState } from 'react';
import axios from 'axios';

const LAST_COLLECTION_KEY = 'archiveSaver.lastCollectionId';
const SHARED_SAVE_COLLECTION_TITLE = '축구';

const isValidUrl = value => /^https?:\/\/\S+$/i.test(value);

const extractFirstUrl = value => {
  if (!value) {
    return '';
  }

  const match = value.match(/https?:\/\/[^\s<>"']+/i);
  return match ? match[0] : '';
};

const getSharedUrlFromLocation = () => {
  const params = new URLSearchParams(window.location.search);
  const candidates = [
    params.get('url'),
    params.get('text'),
    params.get('title')
  ];

  for (const candidate of candidates) {
    const trimmed = candidate?.trim();
    if (trimmed && isValidUrl(trimmed)) {
      return trimmed;
    }

    const extracted = extractFirstUrl(trimmed);
    if (isValidUrl(extracted)) {
      return extracted;
    }
  }

  return '';
};

function App() {
  const [url, setUrl] = useState(() => getSharedUrlFromLocation());
  const [sharedUrl] = useState(() => getSharedUrlFromLocation());
  const [collections, setCollections] = useState([]);
  const [selectedCollection, setSelectedCollection] = useState(() => (
    getSharedUrlFromLocation() ? '' : localStorage.getItem(LAST_COLLECTION_KEY) || ''
  ));
  const [status, setStatus] = useState(() => (
    getSharedUrlFromLocation()
      ? `'${SHARED_SAVE_COLLECTION_TITLE}' 컬렉션에 저장할 준비 중입니다.`
      : ''
  ));
  const [progress, setProgress] = useState(0);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [autoSaveAttempted, setAutoSaveAttempted] = useState(false);

  useEffect(() => {
    if (sharedUrl) {
      return;
    }

    const tryReadClipboard = () => {
      if (document.hasFocus()) {
        navigator.clipboard.readText().then(text => {
          const trimmed = text.trim();
          if (isValidUrl(trimmed)) {
            setUrl(trimmed);
          }
        }).catch(err => {
          console.warn('클립보드 읽기 실패:', err);
        });
      }
    };
    const timer = setTimeout(tryReadClipboard, 300);
    return () => clearTimeout(timer);
  }, [sharedUrl]);

  const loadCollections = () => {
    setIsRefreshing(true);
    axios.get('/api/collections')
      .then(res => setCollections(res.data))
      .catch(err => setStatus('컬렉션 불러오기 실패'))
      .finally(() => setTimeout(() => setIsRefreshing(false), 500));
  };

  useEffect(() => {
    loadCollections();
  }, []);

  useEffect(() => {
    if (!sharedUrl || !collections.length || selectedCollection) {
      return;
    }

    const sharedCollection = collections.find(collection => (
      collection.title === SHARED_SAVE_COLLECTION_TITLE
    ));

    if (!sharedCollection) {
      setStatus(`'${SHARED_SAVE_COLLECTION_TITLE}' 컬렉션을 찾지 못했습니다.`);
      return;
    }

    setSelectedCollection(sharedCollection._id);
    setStatus(`'${SHARED_SAVE_COLLECTION_TITLE}' 컬렉션에 자동 저장합니다.`);
  }, [collections, selectedCollection, sharedUrl]);

  const handleCollectionChange = event => {
    const collectionId = event.target.value;
    setSelectedCollection(collectionId);
    localStorage.setItem(LAST_COLLECTION_KEY, collectionId);
  };

  const savePage = useCallback(async targetUrl => {
    const trimmedUrl = targetUrl.trim();

    if (!isValidUrl(trimmedUrl)) {
      setStatus('❌ 유효하지 않은 URL 형식입니다.');
      return;
    }

    if (!selectedCollection) {
      setStatus('컬렉션을 먼저 선택해주세요.');
      return;
    }

    setIsSaving(true);
    setProgress(10);
    setStatus('페이지 HTML 가져오는 중...');
    try {
      let text = '';

      try {
        const response = await fetch(trimmedUrl);
        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`);
        }
        text = await response.text();
      } catch (fetchError) {
        console.warn('브라우저 HTML 가져오기 실패, 서버에서 다시 시도합니다:', fetchError);
        setStatus('브라우저에서 읽지 못해 서버에서 다시 가져오는 중...');
      }

      setProgress(40);
      setStatus('서버에 저장 요청 중...');

      const res = await axios.post('/api/save-html', {
        url: trimmedUrl,
        html: text,
        collectionId: selectedCollection
      });

      setProgress(100);
      setStatus(res.data.message || '저장 성공!');
    } catch (err) {
      setStatus('저장 실패: ' + err.message);
    } finally {
      setIsSaving(false);
    }
  }, [selectedCollection]);

  useEffect(() => {
    if (!sharedUrl || !selectedCollection || autoSaveAttempted) {
      return;
    }

    setAutoSaveAttempted(true);
    savePage(sharedUrl);
  }, [autoSaveAttempted, savePage, selectedCollection, sharedUrl]);

  const handleSubmit = () => savePage(url);

  const isButtonDisabled = !url || !selectedCollection || isSaving;

  return (
    <div style={styles.fullscreenCentered}>
      <div style={styles.container}>
        <div style={styles.titleRow}>
          <h2>Archive Saver</h2>
          <button
            type="button"
            aria-label="컬렉션 새로고침"
            onClick={loadCollections}
            style={{
              ...styles.refreshButton,
              transform: isRefreshing ? 'rotate(360deg)' : 'none',
              transition: 'transform 0.6s ease-in-out'
            }}
            disabled={isRefreshing}
          >⟳</button>
        </div>
        {sharedUrl && (
          <div style={styles.shareNotice}>
            Android 공유 링크는 축구 컬렉션에 저장합니다.
          </div>
        )}
        <input
          type="text"
          placeholder="URL 입력"
          value={url}
          onChange={e => setUrl(e.target.value)}
          style={styles.input}
        />
        <select
          value={selectedCollection || ''}
          onChange={handleCollectionChange}
          style={styles.select}
        >
          <option value="" disabled>컬렉션 선택</option>
          {collections.map(col => (
            <option key={col._id} value={col._id}>{col.title}</option>
          ))}
        </select>
        <button
          type="button"
          onClick={handleSubmit}
          style={{
            ...styles.button,
            backgroundColor: isButtonDisabled ? '#666' : styles.button.backgroundColor,
            cursor: isButtonDisabled ? 'not-allowed' : 'pointer'
          }}
          disabled={isButtonDisabled}
        >
          {isSaving ? '저장 중...' : '저장하기'}
        </button>
        <div style={styles.status}>{status}</div>
        <div style={styles.progressWrapper}>
          <div style={{ ...styles.progressBar, width: `${progress}%` }} />
        </div>
      </div>
    </div>
  );
}

const styles = {
  fullscreenCentered: {
    display: 'flex',
    justifyContent: 'center',
    alignItems: 'center',
    minHeight: '100vh',
    width: '100vw',
    backgroundColor: '#111827',
    padding: '16px',
    boxSizing: 'border-box'
  },
  container: {
    width: '100%',
    maxWidth: '480px',
    display: 'flex',
    flexDirection: 'column',
    gap: '10px',
    fontFamily: 'sans-serif',
    backgroundColor: '#1f2937',
    color: '#f9fafb',
    borderRadius: '8px',
    padding: '24px',
    boxShadow: '0 4px 16px rgba(0, 0, 0, 0.3)'
  },
  titleRow: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center'
  },
  shareNotice: {
    padding: '10px 12px',
    borderRadius: '6px',
    backgroundColor: '#0f766e',
    color: '#ecfeff',
    fontSize: '14px',
    textAlign: 'left'
  },
  input: {
    padding: '10px',
    fontSize: '15px',
    borderRadius: '6px',
    border: '1px solid #555',
    backgroundColor: '#3a3a3c',
    color: '#fff'
  },
  select: {
    padding: '10px',
    fontSize: '15px',
    borderRadius: '6px',
    border: '1px solid #555',
    backgroundColor: '#3a3a3c',
    color: '#fff'
  },
  button: {
    padding: '12px',
    backgroundColor: '#0a84ff',
    color: 'white',
    borderRadius: '6px',
    border: 'none',
    cursor: 'pointer',
    fontWeight: 'bold',
    fontSize: '16px'
  },
  refreshButton: {
    background: 'none',
    border: 'none',
    fontSize: '22px',
    cursor: 'pointer',
    color: '#aaa',
    marginLeft: '8px'
  },
  status: {
    fontSize: '14px',
    minHeight: '20px'
  },
  progressWrapper: {
    height: '10px',
    backgroundColor: '#3a3a3c',
    borderRadius: '6px',
    overflow: 'hidden',
    marginTop: '4px'
  },
  progressBar: {
    height: '100%',
    backgroundColor: '#32d74b',
    transition: 'width 0.3s ease-in-out'
  }
};

export default App;
