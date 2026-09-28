(() => {
    const toggle = document.getElementById('admin-sidebar-toggle');
    const sidebar = document.getElementById('admin-sidebar');
    if (!toggle || !sidebar) return;
    const key = 'recognition-validator.admin-navigation-collapsed';
    function apply(collapsed) {
        sidebar.hidden = collapsed;
        document.body.dataset.navigationCollapsed = String(collapsed);
        toggle.setAttribute('aria-expanded', String(!collapsed));
        const label = collapsed ? 'Show navigation' : 'Hide navigation';
        toggle.setAttribute('aria-label', label);
        toggle.title = label;
    }
    let collapsed = false;
    try { collapsed = localStorage.getItem(key) === 'true'; } catch (_) { /* Navigation works without storage. */ }
    apply(collapsed);
    toggle.hidden = false;
    toggle.addEventListener('click', () => {
        apply(!sidebar.hidden);
        try { localStorage.setItem(key, String(sidebar.hidden)); } catch (_) { /* Keep the current page usable. */ }
    });
})();
