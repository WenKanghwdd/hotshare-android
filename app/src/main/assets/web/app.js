/**
 * HotShare — Web UI 主逻辑
 * 功能：上传、下载、文件列表、进度追踪
 */

(function() {
    'use strict';

    // ===== 配置 =====
    const POLL_INTERVAL = 3000;   // 文件列表刷新间隔 (ms)
    const DOMAIN = window.location.origin;

    // ===== 状态 =====
    let isUploading = false;
    let currentFiles = [];
    let uploadStartTime = 0;
    let lastLoadedBytes = 0;

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

    // ===== 工具函数 =====

    /** 格式化文件大小 */
    function formatSize(bytes) {
        if (!bytes || bytes === 0) return '0 B';
        const units = ['B', 'KB', 'MB', 'GB', 'TB'];
        const i = Math.floor(Math.log(bytes) / Math.log(1024));
        const val = bytes / Math.pow(1024, i);
        return (i === 0) ? `${val} ${units[i]}` : `${val.toFixed(1)} ${units[i]}`;
    }

    /** 格式化时间 */
    function formatTime(ts) {
        try {
            const d = new Date(ts);
            const pad = (n) => String(n).padStart(2, '0');
            return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
        } catch {
            return '-';
        }
    }

    /** 格式化速度 */
    function formatSpeed(bytesPerSec) {
        if (bytesPerSec < 1024) return `${bytesPerSec.toFixed(0)} B/s`;
        if (bytesPerSec < 1024 * 1024) return `${(bytesPerSec / 1024).toFixed(1)} KB/s`;
        return `${(bytesPerSec / (1024 * 1024)).toFixed(1)} MB/s`;
    }

    /** 获取文件扩展名对应的图标 */
    function getFileIcon(name) {
        const ext = name.split('.').pop().toLowerCase();
        const icons = {
            'jpg': '🖼️', 'jpeg': '🖼️', 'png': '🖼️', 'gif': '🖼️', 'webp': '🖼️', 'svg': '🖼️',
            'mp4': '🎬', 'mov': '🎬', 'mkv': '🎬', 'avi': '🎬',
            'mp3': '🎵', 'wav': '🎵', 'aac': '🎵', 'flac': '🎵',
            'pdf': '📕', 'doc': '📄', 'docx': '📄', 'xls': '📊', 'xlsx': '📊',
            'zip': '📦', 'rar': '📦', 'gz': '📦', '7z': '📦',
            'apk': '📱', 'txt': '📝', 'json': '📋',
        };
        return icons[ext] || '📄';
    }

    /** 显示 Toast 消息 */
    function showToast(msg, duration = 2500) {
        const old = document.querySelector('.toast');
        if (old) old.remove();
        const el = document.createElement('div');
        el.className = 'toast';
        el.textContent = msg;
        document.body.appendChild(el);
        setTimeout(() => el.remove(), duration);
    }

    /** 更新连接状态 */
    function setConnected(ok) {
        statusDot.className = 'dot ' + (ok ? 'connected' : 'disconnected');
        statusText.textContent = ok ? `已连接 — ${DOMAIN.replace(/^https?:\/\//, '')}` : '连接断开';
        if (ok) {
            $('#refreshBtn').style.display = 'none';
        } else {
            $('#refreshBtn').style.display = 'inline';
        }
    }

    // ===== 获取文件列表 =====

    async function loadFileList() {
        try {
            const res = await fetch('/api/files');
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            const files = await res.json();
            currentFiles = files;
            setConnected(true);
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
        fileCount.textContent = `${files.length} 个文件`;

        if (files.length === 0) {
            fileList.innerHTML = `
                <div class="empty-state">
                    <div class="empty-icon">📭</div>
                    <p>暂无文件</p>
                    <p style="font-size:0.8rem;color:var(--text-secondary);margin-top:4px">
                        从 Mac 上传文件后，会显示在这里
                    </p>
                </div>
            `;
            return;
        }

        fileList.innerHTML = files.map(f => `
            <div class="file-item" data-name="${escapeHtml(f.name)}">
                <span class="file-icon">${getFileIcon(f.name)}</span>
                <div class="file-info">
                    <div class="file-name" title="${escapeHtml(f.name)}">${escapeHtml(f.name)}</div>
                    <div class="file-meta">${formatSize(f.size)} · ${formatTime(f.mtime)}</div>
                </div>
                <a class="download-btn" href="/api/download/${f.encodedName}" download>下载</a>
                <button class="delete-btn" data-filename="${f.encodedName}" title="删除">✕</button>
            </div>
        `).join('');

        // 绑定删除事件
        fileList.querySelectorAll('.delete-btn').forEach(btn => {
            btn.addEventListener('click', async (e) => {
                e.stopPropagation();
                const name = btn.dataset.filename;
                if (confirm('确定删除这个文件？')) {
                    await deleteFile(name);
                }
            });
        });
    }

    function escapeHtml(str) {
        const div = document.createElement('div');
        div.textContent = str;
        return div.innerHTML;
    }

    // ===== 删除文件 =====

    async function deleteFile(encodedName) {
        try {
            const res = await fetch(`/api/files/${encodedName}`, { method: 'DELETE' });
            if (res.ok) {
                showToast('🗑️ 文件已删除');
                loadFileList();
            }
        } catch (e) {
            showToast('❌ 删除失败');
        }
    }

    // ===== 上传文件 =====

    async function uploadFiles(files) {
        if (isUploading || files.length === 0) return;
        isUploading = true;

        // 显示进度
        dropInner.hidden = true;
        progressContainer.hidden = false;
        progressFill.style.width = '0%';

        let totalFiles = files.length;
        let completedFiles = 0;
        let totalBytes = Array.from(files).reduce((s, f) => s + f.size, 0);
        let uploadedBytes = 0;
        uploadStartTime = Date.now();
        lastLoadedBytes = 0;

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
                            const total = totalBytes;
                            const pct = Math.min(Math.round((currentLoaded / total) * 100), 99);

                            progressFill.style.width = pct + '%';
                            progressPercent.textContent = pct + '%';

                            // 速度计算
                            const elapsed = (Date.now() - uploadStartTime) / 1000;
                            if (elapsed > 0.5) {
                                const speed = currentLoaded / elapsed;
                                const fileProgress = (i > 0)
                                    ? `(${completedFiles}/${totalFiles} 完成)`
                                    : '';
                                progressSpeed.textContent = `${formatSpeed(speed)} ${fileProgress}`;
                            }
                        }
                    };

                    xhr.onload = () => {
                        if (xhr.status === 200) {
                            completedFiles++;
                            uploadedBytes += file.size;
                            resolve();
                        } else {
                            reject(new Error(`HTTP ${xhr.status}`));
                        }
                    };

                    xhr.onerror = () => reject(new Error('网络错误'));
                    xhr.send(formData);
                });
            } catch (e) {
                console.error(`上传失败: ${file.name}`, e);
                showToast(`❌ ${file.name} 上传失败`);
            }
        }

        // 完成
        progressFill.style.width = '100%';
        progressPercent.textContent = '100%';
        const totalTime = ((Date.now() - uploadStartTime) / 1000).toFixed(1);
        progressFileName.textContent = `✅ 上传完成！共 ${completedFiles}/${totalFiles} 个文件 (${totalTime}s)`;
        progressSpeed.textContent = '';

        isUploading = false;

        // 3s 后恢复上传界面
        setTimeout(() => {
            progressContainer.hidden = true;
            dropInner.hidden = false;
            progressFill.style.width = '0%';
        }, 3000);

        // 刷新文件列表
        loadFileList();
    }

    // ===== 事件绑定 =====

    // 拖拽上传
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
        const files = e.dataTransfer.files;
        if (files.length > 0) {
            uploadFiles(files);
        }
    });

    // 点击选择文件
    selectBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        fileInput.click();
    });

    dropZone.addEventListener('click', (e) => {
        // 防止点进度条也触发放到选择
        if (e.target.closest('.progress-container')) return;
        fileInput.click();
    });

    fileInput.addEventListener('change', (e) => {
        uploadFiles(e.target.files);
        fileInput.value = '';  // 允许重复选择同一文件
    });

    // 全局拖拽（防止拖到页面空白区域也触发浏览器默认行为）
    document.addEventListener('dragover', (e) => e.preventDefault());
    document.addEventListener('drop', (e) => e.preventDefault());

    // ===== 启动 =====
    setConnected(false);
    loadFileList();
    setInterval(loadFileList, POLL_INTERVAL);

})();
