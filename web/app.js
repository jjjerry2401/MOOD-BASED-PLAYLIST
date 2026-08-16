const moodButtons = document.querySelectorAll('.mood-button');
const navLinks = document.querySelectorAll('.sidebar-nav .nav-link');
const generateButton = document.getElementById('generateBtn');
const currentMoodDisplay = document.getElementById('currentMood');
const moodBadge = document.getElementById('moodBadge');
const trackList = document.getElementById('trackList');
const energyFill = document.getElementById('energyFill');
const positivityFill = document.getElementById('positivityFill');
const energyThumb = document.getElementById('energyThumb');
const valenceThumb = document.getElementById('valenceThumb');
const energySliderFill = document.getElementById('energySliderFill');
const valenceSliderFill = document.getElementById('valenceSliderFill');
const energyRange = document.getElementById('energyRange');
const valenceRange = document.getElementById('valenceRange');
const energyValue = document.getElementById('energyValue');
const valenceValue = document.getElementById('valenceValue');
const feelingStatement = document.getElementById('feelingStatement');
const viewSections = document.querySelectorAll('[data-view]');
const moodLabInfo = document.getElementById('moodLabInfo');
const playlistTabInfo = document.getElementById('playlistTabInfo');
const historyLastSong = document.getElementById('historyLastSong');

let selectedMood = 'Focused';
let selectedEnergy = energyRange ? Number(energyRange.value) : 58;
let selectedValence = valenceRange ? Number(valenceRange.value) : 63;
let lastDirectedSong = null;

function applyMoodClass(mood) {
    const moodMap = {
        'Calm': 'mood-calm',
        'Energetic': 'mood-energetic',
        'Focused': 'mood-focused',
        'Melancholy': 'mood-melancholy',
        'Stressed': 'mood-stressed',
        'Joyful': 'mood-joyful'
    };

    const className = moodMap[mood] || 'mood-focused';
    moodBadge.className = 'mood-badge ' + className;

    if (mood === 'Energetic') {
        moodBadge.textContent = 'ENERGY';
    } else if (mood === 'Melancholy') {
        moodBadge.textContent = 'LOW LIGHT';
    } else if (mood === 'Stressed') {
        moodBadge.textContent = 'RECOVER';
    } else if (mood === 'Joyful') {
        moodBadge.textContent = 'SUN UP';
    } else {
        moodBadge.textContent = mood.toUpperCase();
    }
}

moodButtons.forEach((button) => {
    button.addEventListener('click', () => {
        moodButtons.forEach((item) => item.classList.toggle('active', item === button));
        selectedMood = button.dataset.mood;
        currentMoodDisplay.textContent = selectedMood;
        applyMoodClass(selectedMood);
    });
});

navLinks.forEach((link) => {
    link.addEventListener('click', (event) => {
        event.preventDefault();
        const view = link.dataset.panel;
        if (view) {
            switchView(view);
        }
    });
});

generateButton.addEventListener('click', () => {
    const apiUrl = new URL('/api/mood', window.location.origin);
    apiUrl.searchParams.set('mood', selectedMood);
    apiUrl.searchParams.set('energy', selectedEnergy);
    apiUrl.searchParams.set('valence', selectedValence);

    fetch(apiUrl.toString())
        .then((response) => {
            if (!response.ok) {
                return response.text().then((message) => {
                    throw new Error(message || 'Unable to build playlist');
                });
            }
            return response.text();
        })
        .then((text) => {
            const lines = text.split('\n');
            const moodLine = lines[0];
            const descriptionLine = lines[1];

            const moodValue = moodLine.replace('Mood: ', '');
            currentMoodDisplay.textContent = moodValue.trim();
            applyMoodClass(moodValue.trim());

            const tracks = [];
            for (let i = 3; i < lines.length - 1; i++) {
                const line = lines[i];
                if (line.trim().startsWith('-')) {
                    const clean = line.replace('- ', '');
                    const trackParts = clean.split(' | ');
                    const titleArtist = trackParts[0];
                    const artistTitle = titleArtist.split(' by ');
                    const title = artistTitle[0];
                    const artist = artistTitle[1] || '';

                    const track = {
                        title,
                        artist,
                        album: trackParts[1] ? trackParts[1].replace('Album: ', '') : 'Unknown',
                        bpm: trackParts[2] ? trackParts[2].replace('BPM: ', '') : '-',
                        energy: trackParts[3] ? trackParts[3].replace('Energy: ', '') : '0',
                        positivity: trackParts[4] ? trackParts[4].replace('Positivity: ', '') : '0',
                        duration: trackParts.length >= 7 ? trackParts[5].replace('Duration: ', '') : '3:42',
                        spotify: trackParts.length >= 7 ? trackParts[6].replace('Spotify: ', '') : (trackParts[5] ? trackParts[5].replace('Spotify: ', '') : '#')
                    };

                    tracks.push(track);
                }
            }

            renderTrackList(tracks);
            updateSummary(tracks);
            updatePlaylistHeader(tracks);
            updateTabInfo(tracks);
            // Do not auto-switch to the playlist panel anymore — keep user on current view
        })
        .catch((error) => {
            console.error(error);
            currentMoodDisplay.textContent = 'Playlist unavailable';
        });
});

function switchView(view) {
    viewSections.forEach((section) => {
        section.classList.toggle('hidden', section.dataset.view !== view);
    });

    navLinks.forEach((link) => {
        link.classList.toggle('active', link.dataset.panel === view);
    });
}

function renderTrackList(tracks) {
    trackList.innerHTML = '';

    tracks.forEach((track, index) => {
        const li = document.createElement('li');
        li.className = 'track-row';

        const indexDiv = document.createElement('div');
        indexDiv.className = 'track-index';
        indexDiv.textContent = String(index + 1).padStart(2, '0');

        const info = document.createElement('div');
        const title = document.createElement('a');
        title.className = 'track-title';
        title.textContent = track.title;
        title.href = track.spotify || '#';
        title.target = '_blank';
        title.rel = 'noopener noreferrer';

            // register click to add to recently viewed history (also opens externally)
            title.addEventListener('click', (e) => {
                try {
                    addHistory({ title: track.title, artist: track.artist, href: title.href });
                } catch (err) {
                    console.warn('history add failed', err);
                }
            });

        const meta = document.createElement('div');
        meta.className = 'track-meta';
        meta.textContent = track.artist + ' • ' + track.album;

        info.appendChild(title);
        info.appendChild(meta);

        const bpm = document.createElement('div');
        bpm.className = 'track-bpm';
        bpm.textContent = track.bpm + ' BPM';

        const duration = document.createElement('div');
        duration.className = 'track-duration';
        duration.textContent = track.duration || '3:42';

        li.appendChild(indexDiv);
        li.appendChild(info);
        li.appendChild(bpm);
        li.appendChild(duration);

        // star favorite button
        const star = document.createElement('button');
        star.className = 'star-button';
        star.setAttribute('aria-label', 'Favorite');
        // initial state
        if (isFavorited({ title: track.title, artist: track.artist })) {
            star.classList.add('star-on');
            star.textContent = '★';
        } else {
            star.textContent = '☆';
        }

        star.addEventListener('click', (e) => {
            e.preventDefault();
            e.stopPropagation();
            toggleFavorite({ title: track.title, artist: track.artist, href: title.href });
        });

        li.appendChild(star);

        trackList.appendChild(li);
    });
}

function updatePlaylistHeader(tracks) {
    const trackCount = document.getElementById('trackCount');
    const playlistDuration = document.getElementById('playlistDuration');
    const playerTrack = document.getElementById('playerTrack');
    const currentDescription = document.getElementById('currentDescription');

    if (trackCount) {
        trackCount.textContent = `${tracks.length} tracks`;
    }

    if (playlistDuration) {
        const totalSeconds = tracks.reduce((sum, track) => {
            const parts = (track.duration || '0:00').split(':').map(Number);
            return sum + (parts[0] * 60 + (parts[1] || 0));
        }, 0);
        const minutes = Math.floor(totalSeconds / 60);
        const seconds = totalSeconds % 60;
        playlistDuration.textContent = `${minutes}:${seconds.toString().padStart(2, '0')} min`;
    }

    if (playerTrack && tracks[0]) {
        playerTrack.textContent = `${tracks[0].title} • ${tracks[0].artist}`;
    }

    if (currentDescription) {
        currentDescription.textContent = `Curated for ${selectedMood.toLowerCase()} energy, balance, and good flow.`;
    }
}

function updateSummary(tracks) {
    if (!tracks || tracks.length === 0) {
        return;
    }

    const avgEnergy = Math.round(tracks.reduce((sum, track) => sum + Number(track.energy), 0) / tracks.length);
    const avgPositive = Math.round(tracks.reduce((sum, track) => sum + Number(track.positivity), 0) / tracks.length);

    const energyPercent = Math.max(14, Math.min(100, avgEnergy));
    const valencePercent = Math.max(14, Math.min(100, avgPositive));

    if (energySliderFill) energySliderFill.style.width = energyPercent + '%';
    if (valenceSliderFill) valenceSliderFill.style.width = valencePercent + '%';
    updateFeelingDescription();
}

function getFeelingDescription(energy, valence) {
    if (valence >= 70) {
        if (energy >= 70) return 'energized, joyful, and ready to move.';
        if (energy >= 40) return 'calm, happy, and steady.';
        return 'peaceful, relaxed, and content.';
    }
    if (valence >= 40) {
        if (energy >= 70) return 'focused, active, and alert.';
        if (energy >= 40) return 'balanced, present, and measured.';
        return 'gentle, quiet, and thoughtful.';
    }
    if (energy >= 70) return 'restless, anxious, or tense.';
    if (energy >= 40) return 'low, reflective, and a bit heavy.';
    return 'tired, melancholic, and withdrawn.';
}

function updateFeelingDescription() {
    if (!feelingStatement) {
        return;
    }

    const description = getFeelingDescription(selectedEnergy, selectedValence);
    feelingStatement.textContent = `You are currently feeling ${description}`;
}

function updateTabInfo(tracks) {
    if (moodLabInfo) {
        moodLabInfo.innerHTML = `
            <p><strong>Selected mood:</strong> ${selectedMood}</p>
            <p><strong>Energy:</strong> ${selectedEnergy} / <strong>Valence:</strong> ${selectedValence}</p>
            <p>Last generated playlist includes ${tracks.length} tracks.</p>
        `;
    }

    if (playlistTabInfo) {
        playlistTabInfo.innerHTML = `
            <p><strong>Latest playlist:</strong> ${selectedMood} mood with ${tracks.length} tracks.</p>
            <p><strong>Top track:</strong> ${tracks[0]?.title} by ${tracks[0]?.artist}</p>
            <p><strong>Duration:</strong> ${document.getElementById('playlistDuration').textContent}</p>
        `;
    }

    if (historyLastSong) {
        lastDirectedSong = tracks[0] ? `${tracks[0].title} by ${tracks[0].artist}` : lastDirectedSong;
        historyLastSong.innerHTML = `<p><strong>Last directed song:</strong> ${lastDirectedSong || 'None yet'}</p>`;
    }
}

function updateSliderState(range, fill, thumb, valueLabel, value) {
    if (!range || !fill || !thumb || !valueLabel) {
        return;
    }

    const percent = Math.max(14, Math.min(100, Number(value)));
    range.value = percent;
    fill.style.width = percent + '%';
    thumb.style.left = `${percent}%`;
    valueLabel.textContent = String(percent);
    updateFeelingDescription();
}

function initSliderControls() {
    switchView('dashboard');
    if (energyRange) {
        updateSliderState(energyRange, energySliderFill, energyThumb, energyValue, selectedEnergy);
        energyRange.addEventListener('input', () => {
            selectedEnergy = Number(energyRange.value);
            updateSliderState(energyRange, energySliderFill, energyThumb, energyValue, selectedEnergy);
        });
    }

    if (valenceRange) {
        updateSliderState(valenceRange, valenceSliderFill, valenceThumb, valenceValue, selectedValence);
        valenceRange.addEventListener('input', () => {
            selectedValence = Number(valenceRange.value);
            updateSliderState(valenceRange, valenceSliderFill, valenceThumb, valenceValue, selectedValence);
        });
    }
}

initSliderControls();

// ---------- Sidebar and History features ----------
const recentHistoryEl = document.getElementById('recentHistory');
const sidebarButtons = document.querySelectorAll('.sidebar-button');

function loadHistory() {
    try {
        const raw = localStorage.getItem('aura_history');
        return raw ? JSON.parse(raw) : [];
    } catch (e) {
        return [];
    }
}

function saveHistory(list) {
    try {
        localStorage.setItem('aura_history', JSON.stringify(list));
    } catch (e) {
        console.warn('could not save history', e);
    }
}

function renderHistorySidebar() {
    if (!recentHistoryEl) return;
    const history = loadHistory();
    recentHistoryEl.innerHTML = '';
    history.forEach((item, idx) => {
        const li = document.createElement('li');
        const title = document.createElement('div');
        title.className = 'history-title';
        title.textContent = `${item.title} • ${item.artist}`;

        const actions = document.createElement('div');
        const open = document.createElement('button');
        open.textContent = 'Open';
        open.addEventListener('click', () => {
            if (item.href) window.open(item.href, '_blank', 'noopener');
        });

        actions.appendChild(open);
        li.appendChild(title);
        li.appendChild(actions);
        recentHistoryEl.appendChild(li);
    });
}

function addHistory(track) {
    if (!track || !track.title) return;
    const history = loadHistory();
    const key = `${track.title} • ${track.artist}`;
    // remove existing
    const filtered = history.filter((h) => `${h.title} • ${h.artist}` !== key);
    filtered.unshift(track);
    const trimmed = filtered.slice(0, 12);
    saveHistory(trimmed);
    renderHistorySidebar();
}

function clearHistory() {
    localStorage.removeItem('aura_history');
    renderHistorySidebar();
}

// wire sidebar buttons
sidebarButtons.forEach((btn) => {
    const mood = btn.dataset.mood;
    const action = btn.dataset.action;
    if (mood) {
        btn.addEventListener('click', () => {
            selectedMood = mood;
            currentMoodDisplay.textContent = selectedMood;
            applyMoodClass(selectedMood);
            // switch to mood lab panel for adjustments
            switchView('mood-lab');
        });
    }
    if (action === 'open-playlists') {
        btn.addEventListener('click', () => switchView('playlist'));
    }
    if (action === 'open-favs') {
        btn.addEventListener('click', () => switchView('favorites'));
    }
    if (action === 'clear-history') {
        btn.addEventListener('click', () => clearHistory());
    }
});

// initial render
renderHistorySidebar();
renderFavoritesList();

// ---------- Favorites management ----------
function loadFavorites() {
    try {
        const raw = localStorage.getItem('aura_favorites');
        return raw ? JSON.parse(raw) : [];
    } catch (e) {
        return [];
    }
}

function saveFavorites(list) {
    try {
        localStorage.setItem('aura_favorites', JSON.stringify(list));
    } catch (e) {
        console.warn('could not save favorites', e);
    }
}

function isFavorited(track) {
    const favs = loadFavorites();
    return favs.some((f) => f.title === track.title && f.artist === track.artist);
}

function toggleFavorite(track) {
    const favs = loadFavorites();
    const idx = favs.findIndex((f) => f.title === track.title && f.artist === track.artist);
    if (idx >= 0) {
        favs.splice(idx, 1);
    } else {
        favs.unshift(track);
    }
    saveFavorites(favs.slice(0, 200));
    renderFavoritesList();
    updateTrackStars();
}

function updateFavoritesCount() {
    const countLabel = document.getElementById('sidebar-favorites-count');
    if (!countLabel) return;
    const favs = loadFavorites();
    countLabel.textContent = `${favs.length} saved`;
}

function renderFavoritesList() {
    const favoritesListEl = document.getElementById('favoritesList');
    if (!favoritesListEl) return;
    const favs = loadFavorites();
    favoritesListEl.innerHTML = '';
    if (favs.length === 0) {
        const li = document.createElement('li');
        li.textContent = 'No favorites yet. Star a song to add it here.';
        favoritesListEl.appendChild(li);
    } else {
        favs.forEach((f) => {
            const li = document.createElement('li');
            li.className = 'fav-row';
            li.textContent = `${f.title} • ${f.artist}`;
            const remove = document.createElement('button');
            remove.textContent = 'Remove';
            remove.style.marginLeft = '8px';
            remove.addEventListener('click', (e) => {
                e.stopPropagation();
                toggleFavorite(f);
            });
            li.appendChild(remove);
            favoritesListEl.appendChild(li);
        });
    }
    updateFavoritesCount();
}

function updateTrackStars() {
    const rows = document.querySelectorAll('.track-row');
    rows.forEach((row) => {
        const titleEl = row.querySelector('.track-title');
        const star = row.querySelector('.star-button');
        if (!titleEl || !star) return;
        const title = titleEl.textContent;
        const meta = row.querySelector('.track-meta')?.textContent || '';
        const artist = meta.split('•')[0]?.trim() || '';
        const fake = { title, artist };
        if (isFavorited(fake)) {
            star.classList.add('star-on');
            star.textContent = '★';
        } else {
            star.classList.remove('star-on');
            star.textContent = '☆';
        }
    });
}
