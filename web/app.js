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
const spotifySearchForm = document.getElementById('spotifySearchForm');
const spotifySearchInput = document.getElementById('spotifySearchInput');
const spotifySearchType = document.getElementById('spotifySearchType');
const spotifySearchStatus = document.getElementById('spotifySearchStatus');
const spotifySearchResults = document.getElementById('spotifySearchResults');
const playlistActionStatus = document.getElementById('playlistActionStatus');
const playlistDialog = document.getElementById('playlistDialog');
const playlistAddForm = document.getElementById('playlistAddForm');
const playlistSelect = document.getElementById('playlistSelect');
const newPlaylistFields = document.getElementById('newPlaylistFields');
const newPlaylistName = document.getElementById('newPlaylistName');
const playlistDialogStatus = document.getElementById('playlistDialogStatus');
const cancelPlaylistAdd = document.getElementById('cancelPlaylistAdd');
const moodImage = document.getElementById('moodImage');
const miniPlayer = document.getElementById('miniPlayer');
const playerTrack = document.getElementById('playerTrack');

let selectedMood = 'Focused';
let selectedEnergy = energyRange ? Number(energyRange.value) : 58;
let selectedValence = valenceRange ? Number(valenceRange.value) : 63;
let lastDirectedSong = null;
let trackToAdd = null;
const NEW_PLAYLIST_VALUE = '__new_playlist__';

if (playlistSelect) {
    playlistSelect.addEventListener('change', updateNewPlaylistFields);
}

if (cancelPlaylistAdd) {
    cancelPlaylistAdd.addEventListener('click', () => playlistDialog.close());
}

if (playlistAddForm) {
    playlistAddForm.addEventListener('submit', (event) => {
        event.preventDefault();
        saveTrackToPlaylist();
    });
}

if (spotifySearchForm) {
    spotifySearchForm.addEventListener('submit', (event) => {
        event.preventDefault();
        searchSpotify();
    });
}

function searchSpotify() {
    const query = spotifySearchInput.value.trim();
    if (!query) {
        return;
    }

    const apiUrl = new URL('/api/search', window.location.origin);
    apiUrl.searchParams.set('q', query);
    apiUrl.searchParams.set('type', spotifySearchType.value);
    spotifySearchStatus.textContent = 'Searching Spotify...';
    spotifySearchResults.innerHTML = '';

    fetch(apiUrl.toString())
        .then((response) => response.text().then((text) => {
            let body;
            try {
                body = JSON.parse(text);
            } catch (error) {
                body = { message: text };
            }
            return { ok: response.ok, body };
        }))
        .then(({ ok, body }) => {
            if (!ok) {
                throw new Error(body.error || body.message || 'Spotify search is unavailable.');
            }
            renderSpotifyResults(body.items || [], body.type);
        })
        .catch((error) => {
            spotifySearchStatus.textContent = error.message;
        });
}

function renderSpotifyResults(items, type) {
    spotifySearchResults.innerHTML = '';
    if (items.length === 0) {
        spotifySearchStatus.textContent = 'No Spotify results found.';
        return;
    }

    spotifySearchStatus.textContent = `${items.length} ${type}${items.length === 1 ? '' : 's'} found`;
    items.forEach((item) => {
        const result = document.createElement('article');
        result.className = 'spotify-result';

        if (item.image) {
            const image = document.createElement('img');
            image.src = item.image;
            image.alt = '';
            image.loading = 'lazy';
            result.appendChild(image);
        }

        const details = document.createElement('div');
        details.className = 'spotify-result-details';
        const title = document.createElement('h3');
        title.textContent = item.name;
        const meta = document.createElement('p');
        meta.textContent = searchResultMeta(item, type);
        details.append(title, meta);

        const open = document.createElement('a');
        open.className = 'search-result-link';
        open.href = item.url || '#';
        open.target = '_blank';
        open.rel = 'noopener noreferrer';
        open.textContent = 'Open';
        details.appendChild(open);
        if (type === 'track') {
            details.appendChild(createAddToPlaylistButton({
                title: item.name,
                artist: item.artist || 'Unknown artist',
                album: item.album || '',
                duration: item.duration || '',
                spotify: item.url || '#'
            }));
        }
        result.appendChild(details);
        spotifySearchResults.appendChild(result);
    });
}

function searchResultMeta(item, type) {
    if (type === 'track') return `${item.artist || 'Unknown artist'}${item.album ? ` • ${item.album}` : ''}${item.duration !== '0:00' ? ` • ${item.duration}` : ''}`;
    if (type === 'album') return `${item.artist || 'Unknown artist'}${item.releaseDate ? ` • ${item.releaseDate}` : ''}`;
    if (type === 'playlist') return `${item.owner || 'Spotify playlist'}${item.total ? ` • ${item.total} tracks` : ''}`;
    if (type === 'episode') return `${item.show || 'Podcast'}${item.releaseDate ? ` • ${item.releaseDate}` : ''}${item.duration !== '0:00' ? ` • ${item.duration}` : ''}`;
    return item.artist || 'Spotify artist';
}

function createAddToPlaylistButton(track) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'add-to-playlist-button';
    button.textContent = 'Add to playlist';
    button.addEventListener('click', (event) => {
        event.preventDefault();
        event.stopPropagation();
        openPlaylistDialog(track);
    });
    return button;
}

function openPlaylistDialog(track) {
    if (!playlistDialog || !playlistSelect || !playlistAddForm) {
        return;
    }

    trackToAdd = { ...track };
    playlistSelect.innerHTML = '';
    playlistSelect.add(new Option('Create a new playlist…', NEW_PLAYLIST_VALUE));
    loadPlaylists().forEach((playlist) => {
        playlistSelect.add(new Option(playlist.name, playlist.name));
    });
    playlistAddForm.reset();
    playlistSelect.value = NEW_PLAYLIST_VALUE;
    newPlaylistName.value = '';
    playlistDialogStatus.textContent = '';
    updateNewPlaylistFields();
    playlistDialog.showModal();
    if (playlistSelect.value === NEW_PLAYLIST_VALUE) {
        newPlaylistName.focus();
    }
}

function updateNewPlaylistFields() {
    if (!playlistSelect || !newPlaylistFields || !newPlaylistName) {
        return;
    }

    const creatingPlaylist = playlistSelect.value === NEW_PLAYLIST_VALUE;
    newPlaylistFields.hidden = !creatingPlaylist;
    newPlaylistName.required = creatingPlaylist;
}

function saveTrackToPlaylist() {
    if (!trackToAdd || !playlistSelect) {
        return;
    }

    const playlists = loadPlaylists();
    let playlist;
    if (playlistSelect.value === NEW_PLAYLIST_VALUE) {
        const name = newPlaylistName.value.trim();
        if (!name) {
            playlistDialogStatus.textContent = 'Enter a name for the new playlist.';
            newPlaylistName.focus();
            return;
        }
        if (playlists.some((item) => item.name.toLocaleLowerCase() === name.toLocaleLowerCase())) {
            playlistDialogStatus.textContent = 'That playlist name already exists. Select it from the list instead.';
            return;
        }
        playlist = { name, tracks: [] };
        playlists.push(playlist);
    } else {
        playlist = playlists.find((item) => item.name === playlistSelect.value);
        if (!playlist) {
            playlistDialogStatus.textContent = 'Choose an existing playlist or create a new one.';
            return;
        }
    }

    const duplicate = playlist.tracks.some((item) =>
        item.title.toLocaleLowerCase() === trackToAdd.title.toLocaleLowerCase()
        && item.artist.toLocaleLowerCase() === trackToAdd.artist.toLocaleLowerCase());
    if (duplicate) {
        playlistDialogStatus.textContent = `“${trackToAdd.title}” is already in “${playlist.name}”.`;
        return;
    }

    playlist.tracks.push(trackToAdd);
    if (!savePlaylists(playlists)) {
        playlistActionStatus.textContent = 'Could not save this playlist in your browser storage.';
        return;
    }

    renderPlaylists();
    playlistActionStatus.textContent = `Added “${trackToAdd.title}” to “${playlist.name}”.`;
    playlistDialog.close();
    trackToAdd = null;
}

function loadPlaylists() {
    try {
        const raw = localStorage.getItem('aura_playlists');
        const parsed = raw ? JSON.parse(raw) : [];
        if (!Array.isArray(parsed)) {
            console.warn('Saved playlists are not in the expected format.');
            return [];
        }
        return parsed
            .filter((playlist) =>
                playlist && typeof playlist.name === 'string' && Array.isArray(playlist.tracks))
            .map((playlist) => ({
                name: playlist.name,
                tracks: playlist.tracks.filter((track) =>
                    track && typeof track.title === 'string' && typeof track.artist === 'string')
            }));
    } catch (error) {
        console.warn('Could not load saved playlists.', error);
        return [];
    }
}

function savePlaylists(playlists) {
    try {
        localStorage.setItem('aura_playlists', JSON.stringify(playlists));
        return true;
    } catch (error) {
        console.error('Could not save playlists.', error);
        return false;
    }
}

function renderPlaylists() {
    const savedPlaylists = document.getElementById('savedPlaylists');
    const countLabel = document.getElementById('sidebar-playlist-count');
    const playlists = loadPlaylists();

    if (countLabel) {
        countLabel.textContent = `${playlists.length} saved`;
    }
    if (!savedPlaylists) {
        return;
    }

    savedPlaylists.innerHTML = '';
    if (playlists.length === 0) {
        const emptyMessage = document.createElement('p');
        emptyMessage.className = 'empty-playlists-message';
        emptyMessage.textContent = 'No playlists yet. Add a song and create your first playlist.';
        savedPlaylists.appendChild(emptyMessage);
        return;
    }

    playlists.forEach((playlist, playlistIndex) => {
        const card = document.createElement('section');
        card.className = 'saved-playlist-card';
        const heading = document.createElement('div');
        heading.className = 'saved-playlist-heading';
        const title = document.createElement('h3');
        title.textContent = playlist.name;
        const count = document.createElement('span');
        count.textContent = `${playlist.tracks.length} ${playlist.tracks.length === 1 ? 'song' : 'songs'}`;
        heading.append(title, count);
        card.appendChild(heading);

        const tracks = document.createElement('ul');
        tracks.className = 'panel-list saved-playlist-tracks';
        if (playlist.tracks.length === 0) {
            const emptyTrack = document.createElement('li');
            emptyTrack.textContent = 'No songs in this playlist yet.';
            tracks.appendChild(emptyTrack);
        }
        playlist.tracks.forEach((track, trackIndex) => {
            const item = document.createElement('li');
            item.className = 'saved-playlist-track';
            const link = document.createElement('a');
            link.textContent = `${track.title} • ${track.artist}`;
            link.href = track.spotify || track.href || '#';
            link.target = '_blank';
            link.rel = 'noopener noreferrer';
            const remove = document.createElement('button');
            remove.type = 'button';
            remove.className = 'remove-playlist-track';
            remove.textContent = 'Remove';
            remove.addEventListener('click', () => {
                const currentPlaylists = loadPlaylists();
                if (!currentPlaylists[playlistIndex]) {
                    return;
                }
                currentPlaylists[playlistIndex].tracks.splice(trackIndex, 1);
                if (savePlaylists(currentPlaylists)) {
                    renderPlaylists();
                } else {
                    playlistActionStatus.textContent = 'Could not update this playlist in your browser storage.';
                }
            });
            item.append(link, createAddToPlaylistButton(track), remove);
            tracks.appendChild(item);
        });
        card.appendChild(tracks);
        savedPlaylists.appendChild(card);
    });
}

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

    const moodImages = {
        Calm: 'https://images.unsplash.com/photo-1500534623283-312aade485b7?auto=format&fit=crop&w=1200&q=80',
        Energetic: 'https://images.unsplash.com/photo-1501386761578-eac5c94b800a?auto=format&fit=crop&w=1200&q=80',
        Focused: 'https://images.unsplash.com/photo-1519681393784-d120267933ba?auto=format&fit=crop&w=1200&q=80',
        Melancholy: 'https://images.unsplash.com/photo-1516589178581-6cd7833ae3b2?auto=format&fit=crop&w=1200&q=80',
        Stressed: 'https://images.unsplash.com/photo-1470252649378-9c29740c9fa8?auto=format&fit=crop&w=1200&q=80',
        Joyful: 'https://images.unsplash.com/photo-1492684223066-81342ee5ff30?auto=format&fit=crop&w=1200&q=80'
    };

    if (moodImage && moodImages[mood]) {
        moodImage.src = moodImages[mood];
        moodImage.alt = `${mood} mood atmosphere`;
    }

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
                showPlayer(track);
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

        const actions = document.createElement('div');
        actions.className = 'track-actions';
        actions.appendChild(createAddToPlaylistButton(track));
        info.appendChild(actions);

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

function showPlayer(track) {
    if (!miniPlayer || !playerTrack) {
        return;
    }

    playerTrack.textContent = `${track.title} • ${track.artist}`;
    miniPlayer.classList.remove('hidden');
}

function hidePlayer() {
    if (!miniPlayer || !playerTrack) {
        return;
    }

    playerTrack.textContent = '';
    miniPlayer.classList.add('hidden');
}

function updatePlaylistHeader(tracks) {
    const trackCount = document.getElementById('trackCount');
    const playlistDuration = document.getElementById('playlistDuration');
    const currentDescription = document.getElementById('currentDescription');

    hidePlayer();

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
renderPlaylists();

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
            const title = document.createElement('span');
            title.textContent = `${f.title} • ${f.artist}`;
            li.appendChild(title);
            li.appendChild(createAddToPlaylistButton({
                title: f.title,
                artist: f.artist,
                album: f.album || '',
                duration: f.duration || '',
                spotify: f.spotify || f.href || '#'
            }));
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
