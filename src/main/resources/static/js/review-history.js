(() => {
    const dialog = document.getElementById('history-preview');
    if (!dialog) return;
    const image = document.getElementById('history-preview-image');
    const status = document.getElementById('history-preview-status');
    const original = document.getElementById('history-original');
    const retry = document.getElementById('history-preview-retry');
    function load() {
        image.hidden = true;
        retry.hidden = true;
        status.textContent = 'Loading screenshot…';
        image.src = original.href;
    }
    document.querySelectorAll('.history-preview-link').forEach(link => {
        link.addEventListener('click', event => {
            if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
            event.preventDefault();
            document.getElementById('history-preview-title').textContent = link.dataset.historyTitle;
            document.getElementById('history-preview-meta').textContent = link.dataset.historyMeta;
            image.alt = link.dataset.historyTitle;
            original.href = link.href;
            dialog.showModal();
            load();
        });
    });
    image.addEventListener('load', () => {
        if (!dialog.open) return;
        image.hidden = false;
        status.textContent = '';
    });
    image.addEventListener('error', () => {
        if (!dialog.open) return;
        status.textContent = 'Screenshot unavailable. It may have expired, or storage may be temporarily unavailable.';
        retry.hidden = false;
    });
    retry.addEventListener('click', load);
    document.getElementById('history-preview-close').addEventListener('click', () => dialog.close());
    dialog.addEventListener('close', () => {
        image.hidden = true;
        image.removeAttribute('src');
        status.textContent = '';
    });
})();
