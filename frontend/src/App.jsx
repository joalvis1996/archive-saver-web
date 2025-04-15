// src/App.jsx
import React, { useEffect, useState } from 'react';
import axios from 'axios';

function App() {
  const [url, setUrl] = useState('');
  const [htmlContent, setHtmlContent] = useState('');
  const [collections, setCollections] = useState([]);
  const [selectedCollection, setSelectedCollection] = useState(null);
  const [status, setStatus] = useState('');
  const [progress, setProgress] = useState(0);
  const [isRefreshing, setIsRefreshing] = useState(false);

  const isValidUrl = url => /^https?:\/\/.+/.test(url);

  useEffect(() => {
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
  }, []);

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

  const handleSubmit = async () => {
    if (!isValidUrl(url)) {
      setStatus('❌ 유효하지 않은 URL 형식입니다.');
      return;
    }

    setProgress(10);
    setStatus('페이지 HTML 가져오는 중...');
    try {
      const response = await fetch(url);
      const text = await response.text();
      setHtmlContent(text);

      setProgress(40);
      setStatus('서버에 저장 요청 중...');

      const res = await axios.post('/api/save-html', {
        url,
        html: text,
        collectionId: selectedCollection
      });

      setProgress(100);
      setStatus(res.data.message || '저장 성공!');
    } catch (err) {
      setStatus('저장 실패: ' + err.message);
    }
  };

  const isButtonDisabled = !url || !selectedCollection;

  return (
    <div style={styles.fullscreenCentered}>
      <div style={styles.container}>
        <div style={styles.titleRow}>
          <h2>📦 웹페이지 저장기</h2>
          <button
            onClick={loadCollections}
            style={{
              ...styles.refreshButton,
              transform: isRefreshing ? 'rotate(360deg)' : 'none',
              transition: 'transform 0.6s ease-in-out'
            }}
            disabled={isRefreshing}
          >⟳</button>
        </div>
        <input
          type="text"
          placeholder="URL 입력"
          value={url}
          onChange={e => setUrl(e.target.value)}
          style={styles.input}
        />
        <select
          value={selectedCollection || ''}
          onChange={e => setSelectedCollection(e.target.value)}
          style={styles.select}
        >
          <option value="" disabled>컬렉션 선택</option>
          {collections.map(col => (
            <option key={col._id} value={col._id}>{col.title}</option>
          ))}
        </select>
        <button
          onClick={handleSubmit}
          style={{
            ...styles.button,
            backgroundColor: isButtonDisabled ? '#666' : styles.button.backgroundColor,
            cursor: isButtonDisabled ? 'not-allowed' : 'pointer'
          }}
          disabled={isButtonDisabled}
        >
          저장하기
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
    height: '100vh',
    width: '100vw',
    backgroundColor: '#1e1e1e',
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
    backgroundColor: '#2c2c2e',
    color: '#f0f0f0',
    borderRadius: '12px',
    padding: '24px',
    boxShadow: '0 4px 16px rgba(0, 0, 0, 0.3)'
  },
  titleRow: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center'
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