/**
 * First-visit balloon-help tour.
 *
 * Shows a sequence of balloon tips anchored to elements carrying
 * [data-tour], [data-tour-title] and [data-tour-text] attributes (in DOM
 * order). Each balloon has a "Next" and a "Dismiss" button; both close the
 * tour and unblock the UI.
 *
 * The "already shown" marker is a cookie whose value is the application
 * version (window.giteabotTourVersion, set by layout.html). Because the
 * stored value is compared against the running version, a new release
 * automatically re-triggers the tour on the next visit.
 */
(function () {
    'use strict';

    var COOKIE_NAME = 'giteabot_tour_version';
    var COOKIE_MAX_AGE_SECONDS = 60 * 60 * 24 * 365; // 1 year
    var OVERLAY_Z = 1055;

    function getCookie(name) {
        var prefix = name + '=';
        var parts = document.cookie ? document.cookie.split(';') : [];
        for (var i = 0; i < parts.length; i++) {
            var part = parts[i].trim();
            if (part.indexOf(prefix) === 0) {
                return decodeURIComponent(part.substring(prefix.length));
            }
        }
        return null;
    }

    function setCookie(name, value) {
        document.cookie = name + '=' + encodeURIComponent(value)
            + '; path=/; max-age=' + COOKIE_MAX_AGE_SECONDS + '; samesite=lax';
    }

    function injectStyles() {
        var style = document.createElement('style');
        style.textContent =
            '.giteabot-tour-overlay { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.55); z-index: ' + OVERLAY_Z + '; }' +
            '.giteabot-tour-highlight { position: relative; z-index: ' + (OVERLAY_Z + 1) + ';' +
            '  outline: 2px solid #0d6efd; outline-offset: 2px; border-radius: 0.375rem;' +
            '  background-color: rgba(13, 110, 253, 0.15); }' +
            '.giteabot-tour-balloon { position: fixed; z-index: ' + (OVERLAY_Z + 2) + ';' +
            '  max-width: 22rem; background: var(--bs-body-bg, #fff); color: var(--bs-body-color, #212529);' +
            '  border: 1px solid var(--bs-border-color, #dee2e6); border-radius: 0.5rem;' +
            '  box-shadow: 0 0.5rem 1rem rgba(0, 0, 0, 0.35); padding: 1rem; }' +
            '.giteabot-tour-balloon h6 { margin: 0 0 0.5rem 0; }' +
            '.giteabot-tour-balloon p { margin: 0 0 0.75rem 0; }' +
            '.giteabot-tour-step { font-size: 0.8rem; opacity: 0.7; }';
        document.head.appendChild(style);
    }

    document.addEventListener('DOMContentLoaded', function () {
        var version = window.giteabotTourVersion || 'dev';
        if (getCookie(COOKIE_NAME) === version) {
            return;
        }

        var steps = [];
        document.querySelectorAll('[data-tour]').forEach(function (el) {
            // Skip steps whose anchor is not visible (e.g. collapsed mobile nav)
            if (el.offsetParent === null) {
                return;
            }
            steps.push({
                element: el,
                title: el.getAttribute('data-tour-title') || '',
                text: el.getAttribute('data-tour-text') || ''
            });
        });
        if (steps.length === 0) {
            return;
        }

        injectStyles();

        var overlay = document.createElement('div');
        overlay.className = 'giteabot-tour-overlay';
        document.body.appendChild(overlay);

        var balloon = document.createElement('div');
        balloon.className = 'giteabot-tour-balloon';
        balloon.setAttribute('role', 'dialog');
        document.body.appendChild(balloon);

        var current = -1;
        var previousBodyOverflow = document.body.style.overflow;

        // Lock background scrolling while the tour is open so the fixed-position
        // balloon can never detach from its anchor.
        document.body.style.overflow = 'hidden';

        function finish() {
            setCookie(COOKIE_NAME, version);
            steps.forEach(function (step) {
                step.element.classList.remove('giteabot-tour-highlight');
            });
            document.body.style.overflow = previousBodyOverflow;
            overlay.remove();
            balloon.remove();
            document.removeEventListener('keydown', onKeydown);
            window.removeEventListener('resize', onResize);
        }

        function onKeydown(event) {
            if (event.key === 'Escape') {
                finish();
            }
        }

        function onResize() {
            if (current >= 0 && current < steps.length) {
                positionBalloon(steps[current].element);
            }
        }

        function positionBalloon(target) {
            var rect = target.getBoundingClientRect();
            var balloonWidth = Math.min(352, window.innerWidth - 16);
            balloon.style.width = balloonWidth + 'px';
            var left = Math.max(8, Math.min(rect.left, window.innerWidth - balloonWidth - 8));
            var top = rect.bottom + 10;
            if (top + balloon.offsetHeight > window.innerHeight - 8) {
                top = Math.max(8, rect.top - balloon.offsetHeight - 10);
            }
            balloon.style.left = left + 'px';
            balloon.style.top = top + 'px';
        }

        function showStep(index) {
            if (index > 0) {
                steps[index - 1].element.classList.remove('giteabot-tour-highlight');
            }
            if (index >= steps.length) {
                finish();
                return;
            }
            current = index;
            var step = steps[index];
            step.element.classList.add('giteabot-tour-highlight');

            balloon.innerHTML = '';
            var heading = document.createElement('h6');
            heading.textContent = step.title;
            var body = document.createElement('p');
            body.textContent = step.text;
            var footer = document.createElement('div');
            footer.className = 'd-flex justify-content-between align-items-center';

            var counter = document.createElement('span');
            counter.className = 'giteabot-tour-step';
            counter.textContent = (index + 1) + ' / ' + steps.length;

            var buttons = document.createElement('div');
            var dismiss = document.createElement('button');
            dismiss.type = 'button';
            dismiss.className = 'btn btn-sm btn-outline-secondary me-2';
            dismiss.textContent = 'Dismiss';
            dismiss.addEventListener('click', finish);
            var next = document.createElement('button');
            next.type = 'button';
            next.className = 'btn btn-sm btn-primary';
            next.textContent = 'Next';
            next.addEventListener('click', function () {
                showStep(index + 1);
            });
            buttons.appendChild(dismiss);
            buttons.appendChild(next);

            footer.appendChild(counter);
            footer.appendChild(buttons);
            balloon.appendChild(heading);
            balloon.appendChild(body);
            balloon.appendChild(footer);

            positionBalloon(step.element);
        }

        document.addEventListener('keydown', onKeydown);
        window.addEventListener('resize', onResize);
        showStep(0);
    });
})();
