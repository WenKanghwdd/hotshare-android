/**
 * HotShare — Web UI 主逻辑
 * 功能：上传、下载、文件列表、进度追踪、回收站、批量打包、预览
 */

(function() {
    'use strict';

    // ===== 配置 =====
    const POLL_INTERVAL = 3000;
    const DOMAIN = window.location.origin;
    const PREVIEWABLE_IMAGES = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'svg', 'bmp'];
    const PREVIEWABLE_VIDEOS = ['mp4', 'mov', 'webm', 'ogv'];
    const PREVIEWABLE_AUDIO = ['mp3', 'wav', 'aac', 'flac', 'ogg', 'm4a'];
    const PREVIEWABLE = [...PREVIEWABLE_IMAGES, ...PREVIEWABLE_VIDEOS, ...PREVIEWABLE_AUDIO, 'pdf', 'txt', 'json'];

    // ===== 状态 =====
    let isUploading = false;
    let currentFiles = [];
    let selectedFiles = new Set();
    let uploadStartTime = 0;
    let searchQuery = '';
    let sortBy = 'mtime';
    let sortDesc = true;

    // ===== DOM 引用 =====
    const $ = (sel) => document.querySelector(sel);
    const $$ = (sel) => document.querySelectorAll(sel);

    const dropZone = $('#dropZone');
    const dropInner = $('#dropInner');
    const fileInput = $('#fileInput');
    const selectBtn = $('#selectBtn');
    const fileList = $('#fileList');
    const fileCount = $('#fileCount');
    const statusText = $('#statusText');
    const statusDot = $('#statusDot');
    const progressContainer = $('#progressContainer');
    const progressFill = $('#progressFill');
    const progressFileName = $('#progressFileName');
    const progressPercent = $('#progressPercent');
    const progressSpeed = $('#progressSpeed');

    // 工具栏
    const selectAllBtn = $('#selectAllBtn');
    const downloadZipBtn = $('#downloadZipBtn');
    const selectedCount = $('#selectedCount');
    const searchInput = $('#searchInput');
    const sortSelect = $('#sortSelect');

    // 预览弹窗
    const previewOverlay = $('#previewOverlay');
    const previewClose = $('#previewClose');
    const previewContent = $('#previewContent');
    const previewFileName = $('#previewFileName');
    const previewDownloadBtn = $('#previewDownloadBtn');

    // 回收站
    const trashToggle = $('#trashToggle');
    const trashSection = $('#trashSection');
    const trashCount = $('#trashCount');
    const trashList = $('#trashList');
    const emptyTrashBtn = $('#emptyTrashBtn');

    // ===== 工具函数 =====

    function formatSize(bytes) {
        if (!bytes || bytes === 0) return '0 B';
        const units = ['B', 'KB', 'MB', 'GB', 'TB'];
        const i = Math.floor(Math.log(bytes) / Math.log(1024));
        const val = bytes / Math.pow(1024, i);
        return (i === 0) ? `${val} ${units[i]}` : `${val.toFixed(1)} ${units[i]}`;
    }

    function formatTime(ts) {
        try {
            const d = new Date(ts);
            const pad = (n) => String(n).padStart(2, '0');
            return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
        } catch {
            return '-';
        }
    }

    function formatSpeed(bytesPerSec) {
        if (bytesPerSec < 1024) return `${bytesPerSec.toFixed(0)} B/s`;
        if (bytesPerSec < 1024 * 1024) return `${(bytesPerSec / 1024).toFixed(1)} KB/s`;
        return `${(bytesPerSec / (1024 * 1024)).toFixed(1)} MB/s`;
    }

    function getFileExt(name) {
        return name.split('.').pop().toLowerCase();
    }

    function getFileIcon(name) {
        const ext = getFileExt(name);
        const icons = {
            'jpg': '🖼️', 'jpeg': '🖼️', 'png': '🖼️', 'gif': '🖼️', 'webp': '🖼️', 'svg': '🖼️', 'bmp': '🖼️',
            'mp4': '🎬', 'mov': '🎬', 'mkv': '🎬', 'avi': '🎬',
            'mp3': '🎵', 'wav': '🎵', 'aac': '🎵', 'flac': '🎵',
            'pdf': '📕', 'doc': '📄', 'docx': '📄', 'xls': '📊', 'xlsx': '📊',
            'zip': '📦', 'rar': '📦', 'gz': '📦', '7z': '📦',
            'apk': '📱', 'txt': '📝', 'json': '📋', 'html': '🌐', 'htm': '🌐',
        };
        return icons[ext] || '📄';
    }

    function isPreviewable(name) {
        return PREVIEWABLE.includes(getFileExt(name));
    }

    function escapeHtml(str) {
        const div = document.createElement('div');
        div.textContent = str;
        return div.innerHTML;
    }

    function showToast(msg, duration = 2500) {
        const old = document.querySelector('.toast');
        if (old) old.remove();
        const el = document.createElement('div');
        el.className = 'toast';
        el.textContent = msg;
        document.body.appendChild(el);
        setTimeout(() => el.remove(), duration);
    }

    function showConfirm(title, message, confirmText = '确定', cancelText = '取消') {
        return new Promise((resolve) => {
            const overlay = document.createElement('div');
            overlay.className = 'confirm-overlay';
            overlay.innerHTML = `
                <div class="confirm-dialog">
                    <div class="confirm-title">${title}</div>
                    <div class="confirm-message">${message}</div>
                    <div class="confirm-actions">
                        <button class="confirm-btn cancel">${cancelText}</button>
                        <button class="confirm-btn ok danger">${confirmText}</button>
                    </div>
                </div>
            `;
            document.body.appendChild(overlay);

            overlay.querySelector('.cancel').onclick = () => { overlay.remove(); resolve(false); };
            overlay.querySelector('.ok').onclick = () => { overlay.remove(); resolve(true); };
            overlay.addEventListener('click', (e) => { if (e.target === overlay) { overlay.remove(); resolve(false); } });
        });
    }

    /** 更新连接状态和设备数 */
    function setConnected(ok, deviceCount) {
        statusDot.className = 'dot ' + (ok ? 'connected' : 'disconnected');
        const domain = DOMAIN.replace(/^https?:\/\//, '');
        if (ok) {
            const deviceText = deviceCount > 0 ? ` · ${deviceCount} 台设备` : '';
            statusText.textContent = `已连接 ${domain}${deviceText}`;
        } else {
            statusText.textContent = '连接断开';
        }
        $('#refreshBtn').style.display = ok ? 'none' : 'inline';
    }

    // ===== 排序与搜索 =====

    function getSortedFiles(files) {
        let filtered = files;
        if (searchQuery) {
            const q = searchQuery.toLowerCase();
            filtered = files.filter(f => f.name.toLowerCase().includes(q));
        }
        const sorted = [...filtered];
        sorted.sort((a, b) => {
            let cmp = 0;
            if (sortBy === 'name') cmp = a.name.localeCompare(b.name);
            else if (sortBy === 'size') cmp = a.size - b.size;
            else cmp = a.mtime - b.mtime;
            return sortDesc ? -cmp : cmp;
        });
        return sorted;
    }

    searchInput.addEventListener('input', () => {
        searchQuery = searchInput.value;
        renderFileList(currentFiles);
    });

    sortSelect.addEventListener('change', () => {
        const val = sortSelect.value;
        if (val.startsWith('-')) {
            sortBy = val.slice(1);
            sortDesc = true;
        } else {
            sortBy = val;
            sortDesc = false;
        }
        renderFileList(currentFiles);
    });

    // ===== 主文件列表 =====

    async function loadFileList() {
        try {
            const [filesRes, infoRes] = await Promise.all([
                fetch('/api/files'),
                fetch('/api/info').catch(() => null)
            ]);
            if (!filesRes.ok) throw new Error(`HTTP ${filesRes.status}`);
            const files = await filesRes.json();
            currentFiles = files;

            // 获取连接设备数
            const info = infoRes?.ok ? await infoRes.json() : null;
            const deviceCount = info?.connected ?? 0;

            setConnected(true, deviceCount);
            renderFileList(files);
        } catch (e) {
            setConnected(false);
            fileList.innerHTML = `
                <div class="error-state">
                    <p>⚠️ 无法连接到服务器</p>
                    <p style="font-size:0.8rem;margin-top:8px;color:var(--text-secondary)">
                        请确认：
                        <br>1️⃣ 手机已开启热点
                        <br>2️⃣ Mac 已连接该热点 Wi-Fi
                        <br>3️⃣ 手机端 HotShare 服务已启动
                    </p>
                </div>
            `;
        }
    }

    function renderFileList(files) {
        const sorted = getSortedFiles(files);
        fileCount.textContent = `${files.length} 个文件`;
        updateSelectionUI();

        if (sorted.length === 0) {
            fileList.innerHTML = `
                <div class="empty-state">
                    <div class="empty-icon">📭</div>
                    <p>${searchQuery ? '没有匹配的文件' : '暂无文件'}</p>
                    <p style="font-size:0.8rem;color:var(--text-secondary);margin-top:4px">
                        ${searchQuery ? '试试其他关键词' : '从 Mac 拖拽文件上传，或从手机端 App 发送文件'}
                    </p>
                </div>
            `;
            return;
        }

        fileList.innerHTML = sorted.map(f => {
            const encoded = f.encodedName;
            const isSelected = selectedFiles.has(f.name);
            return `
                <div class="file-item ${isSelected ? 'selected' : ''}" data-name="${escapeHtml(f.name)}">
                    <label class="file-checkbox-label" title="选择/取消选择">
                        <input type="checkbox" class="file-checkbox" data-encoded="${encoded}" data-name="${escapeHtml(f.name)}" ${isSelected ? 'checked' : ''}>
                        <span class="checkmark"></span>
                    </label>
                    <span class="file-icon preview-trigger" data-encoded="${encoded}" data-name="${escapeHtml(f.name)}" title="点击预览">${getFileIcon(f.name)}</span>
                    <div class="file-info preview-trigger" data-encoded="${encoded}" data-name="${escapeHtml(f.name)}">
                        <div class="file-name" title="${escapeHtml(f.name)}">${escapeHtml(f.name)}</div>
                        <div class="file-meta">${formatSize(f.size)} · ${formatTime(f.mtime)}</div>
                    </div>
                    <a class="download-btn" href="/api/download/${encoded}" download>下载</a>
                    <button class="delete-btn" data-encoded="${encoded}" data-name="${escapeHtml(f.name)}" title="删除（移入手机回收站）">✕</button>
                </div>
            `;
        }).join('');

        // 绑定 checkbox 事件
        fileList.querySelectorAll('.file-checkbox').forEach(cb => {
            cb.addEventListener('change', () => {
                const name = cb.dataset.name;
                if (cb.checked) {
                    selectedFiles.add(name);
                    cb.closest('.file-item').classList.add('selected');
                } else {
                    selectedFiles.delete(name);
                    cb.closest('.file-item').classList.remove('selected');
                }
                updateSelectionUI();
            });
        });

        // 绑定预览事件（点击 icon 或文件名）
        fileList.querySelectorAll('.preview-trigger').forEach(el => {
            el.addEventListener('click', () => {
                const name = el.dataset.name;
                const encoded = el.dataset.encoded;
                openPreview(name, encoded);
            });
        });

        // 绑定删除事件
        fileList.querySelectorAll('.delete-btn').forEach(btn => {
            btn.addEventListener('click', async (e) => {
                e.stopPropagation();
                await deleteFileToTrash(btn.dataset.encoded, btn.dataset.name);
            });
        });
    }

    // ===== 多选管理 =====

    function updateSelectionUI() {
        const count = selectedFiles.size;
        selectedCount.textContent = count > 0 ? `已选 ${count} 项` : '';
        downloadZipBtn.disabled = count === 0;
        selectAllBtn.textContent = isAllSelected() ? '取消全选' : '全选';
    }

    function isAllSelected() {
        if (currentFiles.length === 0) return false;
        return currentFiles.every(f => selectedFiles.has(f.name));
    }

    selectAllBtn.addEventListener('click', () => {
        if (isAllSelected()) {
            selectedFiles.clear();
        } else {
            currentFiles.forEach(f => selectedFiles.add(f.name));
        }
        renderFileList(currentFiles);
    });

    downloadZipBtn.addEventListener('click', () => {
        if (selectedFiles.size === 0) return;
        const selected = currentFiles.filter(f => selectedFiles.has(f.name));
        const params = selected.map(f => 'files=' + encodeURIComponent(f.name)).join('&');
        const url = `/api/download-zip?${params}`;
        // 触发下载
        const a = document.createElement('a');
        a.href = url;
        a.download = `HotShare_${selected.length}files.zip`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        showToast(`📦 正在打包下载 ${selected.length} 个文件`);
    });

    // ===== 预览弹窗 =====

    function openPreview(fileName, encodedName) {
        const ext = getFileExt(fileName);
        previewFileName.textContent = fileName;

        let html = '';
        const url = `/api/preview/${encodedName}`;

        if (PREVIEWABLE_IMAGES.includes(ext)) {
            html = `<img src="${url}" alt="${escapeHtml(fileName)}" class="preview-image" onerror="this.outerHTML='<div class=\\'preview-error\\'>⚠️ 无法加载图片</div>'">`;
        } else if (PREVIEWABLE_VIDEOS.includes(ext)) {
            html = `<video controls autoplay class="preview-video"><source src="${url}" type="video/${ext === 'mov' ? 'quicktime' : ext}">您的浏览器不支持视频播放</video>`;
        } else if (PREVIEWABLE_AUDIO.includes(ext)) {
            html = `<div class="preview-audio-wrapper"><div class="preview-audio-icon">🎵</div><audio controls autoplay class="preview-audio"><source src="${url}" type="audio/${ext === 'm4a' ? 'mp4' : ext}">您的浏览器不支持音频播放</audio></div>`;
        } else if (ext === 'pdf') {
            html = `<embed src="${url}" type="application/pdf" class="preview-pdf">`;
        } else if (ext === 'txt' || ext === 'json') {
            html = `<div class="preview-text-loading">📖 加载中...</div>`;
            previewContent.innerHTML = html;
            previewOverlay.classList.add('open');
            // 异步加载文本内容
            fetch(url)
                .then(r => r.text())
                .then(text => {
                    const truncated = text.length > 100000 ? text.slice(0, 100000) + '\n\n... (文件过大，仅显示前 100KB)' : text;
                    previewContent.innerHTML = `<pre class="preview-text">${escapeHtml(truncated)}</pre>`;
                })
                .catch(() => {
                    previewContent.innerHTML = '<div class="preview-error">⚠️ 加载失败</div>';
                });
            previewDownloadBtn.href = `/api/download/${encodedName}`;
            previewDownloadBtn.style.display = '';
            return;
        } else {
            html = `<div class="preview-error">⚠️ 该文件类型无法预览<br><span style="font-size:0.8rem;color:var(--text-secondary)">请下载后查看</span></div>`;
        }

        previewContent.innerHTML = html;
        previewDownloadBtn.href = `/api/download/${encodedName}`;
        previewDownloadBtn.style.display = '';
        previewOverlay.classList.add('open');
    }

    function closePreview() {
        previewOverlay.classList.remove('open');
        // 停止视频/音频播放
        previewContent.querySelectorAll('video, audio').forEach(el => el.pause());
    }

    previewClose.addEventListener('click', closePreview);
    previewOverlay.addEventListener('click', (e) => {
        if (e.target === previewOverlay) closePreview();
    });
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') closePreview();
    });

    // ===== 回收站管理 =====

    trashToggle.addEventListener('click', () => {
        const isOpen = trashSection.classList.toggle('open');
        trashToggle.querySelector('.toggle-icon').textContent = isOpen ? '▼' : '▶';
        if (isOpen) loadTrashList();
    });

    async function deleteFileToTrash(encodedName, displayName) {
        const confirmed = await showConfirm(
            '确认移入回收站？',
            `「${displayName}」将被移入手机回收站，可从回收站恢复。<br><br><span style="color:var(--danger);font-size:0.85rem;">⚠️ 注意：文件存储在手机本地，删除后占用空间仍在回收站</span>`,
            '移入回收站',
            '取消'
        );
        if (!confirmed) return;
        try {
            const res = await fetch(`/api/files/${encodedName}`, { method: 'DELETE' });
            if (res.ok) {
                selectedFiles.delete(displayName);
                showToast('🗑️ 已移入回收站');
                loadFileList();
                loadTrashCount();
            } else {
                showToast('❌ 操作失败');
            }
        } catch (e) {
            showToast('❌ 网络错误');
        }
    }

    async function loadTrashCount() {
        try {
            const res = await fetch('/api/trash');
            if (!res.ok) return;
            const items = await res.json();
            trashCount.textContent = `${items.length} 个文件`;
        } catch (e) {}
    }

    async function loadTrashList() {
        try {
            const res = await fetch('/api/trash');
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            const items = await res.json();
            renderTrashList(items);
        } catch (e) {
            trashList.innerHTML = `<div class="empty-state"><p>无法加载回收站</p></div>`;
        }
    }

    function renderTrashList(items) {
        trashCount.textContent = `${items.length} 个文件`;
        if (items.length === 0) {
            trashList.innerHTML = `<div class="empty-state"><div class="empty-icon">🗑️</div><p>回收站为空</p></div>`;
            emptyTrashBtn.style.display = 'none';
            return;
        }
        emptyTrashBtn.style.display = 'inline-block';
        trashList.innerHTML = items.map(f => `
            <div class="trash-item">
                <span class="file-icon">${getFileIcon(f.originalName)}</span>
                <div class="file-info">
                    <div class="file-name" title="${escapeHtml(f.originalName)}">${escapeHtml(f.originalName)}</div>
                    <div class="file-meta">${formatSize(f.size)} · ${formatTime(f.mtime)}</div>
                </div>
                <button class="restore-btn" data-encoded="${f.encodedName}" title="恢复">♻️ 恢复</button>
                <button class="delete-permanent-btn" data-encoded="${f.encodedName}" data-name="${escapeHtml(f.originalName)}" title="永久删除">✕</button>
            </div>
        `).join('');

        trashList.querySelectorAll('.restore-btn').forEach(btn => {
            btn.addEventListener('click', async () => {
                try {
                    const res = await fetch(`/api/trash/restore/${btn.dataset.encoded}`, { method: 'POST' });
                    if (res.ok) {
                        showToast('♻️ 文件已恢复');
                        loadTrashList();
                        loadFileList();
                        loadTrashCount();
                    }
                } catch (e) { showToast('❌ 恢复失败'); }
            });
        });

        trashList.querySelectorAll('.delete-permanent-btn').forEach(btn => {
            btn.addEventListener('click', async () => {
                const confirmed = await showConfirm('永久删除？', `「${btn.dataset.name}」将从回收站永久删除，不可恢复。`, '永久删除', '取消');
                if (!confirmed) return;
                try {
                    const res = await fetch(`/api/trash/${btn.dataset.encoded}`, { method: 'DELETE' });
                    if (res.ok) {
                        showToast('🗑️ 已永久删除');
                        loadTrashList();
                        loadTrashCount();
                    }
                } catch (e) { showToast('❌ 网络错误'); }
            });
        });
    }

    emptyTrashBtn.addEventListener('click', async () => {
        const confirmed = await showConfirm('清空回收站？', '回收站中的所有文件将被永久删除，不可恢复。', '清空回收站', '取消');
        if (!confirmed) return;
        try {
            const res = await fetch('/api/trash/empty', { method: 'DELETE' });
            if (res.ok) {
                showToast('🗑️ 回收站已清空');
                loadTrashList();
                loadTrashCount();
            }
        } catch (e) { showToast('❌ 操作失败'); }
    });

    // ===== 上传文件 =====

    async function uploadFiles(files) {
        if (isUploading || files.length === 0) return;
        isUploading = true;

        dropInner.hidden = true;
        progressContainer.hidden = false;
        progressFill.style.width = '0%';

        let totalFiles = files.length;
        let completedFiles = 0;
        let totalBytes = Array.from(files).reduce((s, f) => s + f.size, 0);
        let uploadedBytes = 0;
        uploadStartTime = Date.now();

        for (let i = 0; i < totalFiles; i++) {
            const file = files[i];
            const formData = new FormData();
            formData.append('file', file);
            progressFileName.textContent = `[${i+1}/${totalFiles}] ${file.name}`;

            try {
                await new Promise((resolve, reject) => {
                    const xhr = new XMLHttpRequest();
                    xhr.open('POST', '/api/upload', true);
                    xhr.upload.onprogress = (e) => {
                        if (e.lengthComputable) {
                            const currentLoaded = uploadedBytes + e.loaded;
                            const pct = Math.min(Math.round((currentLoaded / totalBytes) * 100), 99);
                            progressFill.style.width = pct + '%';
                            progressPercent.textContent = pct + '%';
                            const elapsed = (Date.now() - uploadStartTime) / 1000;
                            if (elapsed > 0.5) {
                                progressSpeed.textContent = `${formatSpeed(currentLoaded / elapsed)} ${i > 0 ? `(${completedFiles}/${totalFiles} 完成)` : ''}`;
                            }
                        }
                    };
                    xhr.onload = () => {
                        if (xhr.status === 200) { completedFiles++; uploadedBytes += file.size; resolve(); }
                        else reject(new Error(`HTTP ${xhr.status}`));
                    };
                    xhr.onerror = () => reject(new Error('网络错误'));
                    xhr.send(formData);
                });
            } catch (e) {
                console.error(`上传失败: ${file.name}`, e);
                showToast(`❌ ${file.name} 上传失败`);
            }
        }

        progressFill.style.width = '100%';
        progressPercent.textContent = '100%';
        const totalTime = ((Date.now() - uploadStartTime) / 1000).toFixed(1);
        progressFileName.textContent = `✅ 上传完成！共 ${completedFiles}/${totalFiles} 个文件 (${totalTime}s)`;
        progressSpeed.textContent = '';
        isUploading = false;

        setTimeout(() => {
            progressContainer.hidden = true;
            dropInner.hidden = false;
            progressFill.style.width = '0%';
        }, 3000);

        loadFileList();
    }

    // ===== 事件绑定 =====

    dropZone.addEventListener('dragover', (e) => {
        e.preventDefault();
        e.stopPropagation();
        dropZone.classList.add('drag-over');
    });

    dropZone.addEventListener('dragleave', (e) => {
        e.preventDefault();
        e.stopPropagation();
        dropZone.classList.remove('drag-over');
    });

    dropZone.addEventListener('drop', (e) => {
        e.preventDefault();
        e.stopPropagation();
        dropZone.classList.remove('drag-over');

        // 检查是否有文件夹
        const items = e.dataTransfer.items;
        if (items && items.length > 0) {
            // 使用 FileSystem API 处理文件夹
            const allEntries = [];
            const promises = [];
            for (let i = 0; i < items.length; i++) {
                const entry = items[i].webkitGetAsEntry ? items[i].webkitGetAsEntry() : null;
                if (entry) {
                    promises.push(traverseEntry(entry, allEntries));
                }
            }
            if (promises.length > 0) {
                Promise.all(promises).then(() => {
                    if (allEntries.length > 0) {
                        showToast(`📁 正在处理 ${allEntries.length} 个文件（含文件夹内文件）`);
                        uploadFiles(allEntries);
                    }
                });
                return;
            }
        }

        const files = e.dataTransfer.files;
        if (files.length > 0) uploadFiles(files);
    });

    /** 递归遍历文件夹中的文件 */
    function traverseEntry(entry, result) {
        return new Promise((resolve) => {
            if (entry.isFile) {
                entry.file((file) => {
                    // 保留子目录路径
                    const path = entry.fullPath.startsWith('/') ? entry.fullPath.slice(1) : entry.fullPath;
                    // File 对象不支持改 name，用 Object.defineProperty
                    Object.defineProperty(file, 'name', { value: path });
                    result.push(file);
                    resolve();
                }, resolve);
            } else if (entry.isDirectory) {
                const reader = entry.createReader();
                const readEntries = () => {
                    reader.readEntries((entries) => {
                        if (entries.length === 0) resolve();
                        else {
                            const promises = [];
                            for (const e of entries) {
                                promises.push(traverseEntry(e, result));
                            }
                            Promise.all(promises).then(readEntries);
                        }
                    }, resolve);
                };
                readEntries();
            } else {
                resolve();
            }
        });
    }

    selectBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        // 支持选择文件夹
        fileInput.setAttribute('webkitdirectory', '');
        fileInput.removeAttribute('webkitdirectory');
        // 正常文件选择
        fileInput.click();
    });

    dropZone.addEventListener('click', (e) => {
        if (e.target.closest('.progress-container')) return;
        if (e.target.closest('.trash-section')) return;
        fileInput.click();
    });

    fileInput.addEventListener('change', (e) => {
        uploadFiles(e.target.files);
        fileInput.value = '';
    });

    document.addEventListener('dragover', (e) => e.preventDefault());
    document.addEventListener('drop', (e) => e.preventDefault());

    // ===== 启动 =====
    setConnected(false);
    loadFileList();
    loadTrashCount();
    setInterval(() => {
        loadFileList();
        loadTrashCount();
    }, POLL_INTERVAL);

})();
