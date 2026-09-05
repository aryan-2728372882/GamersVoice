# GamerVoice - WebRTC Signaling Server

A lightweight, high-performance Node.js WebSocket signaling server for **GamerVoice**. Facilitates peer discovery and WebRTC connection exchange for up to 5 players per room.

---

## 🚀 Deployment Steps (Render.com Free Tier)

### Option A: Using Render Blueprints (Recommended)
1. Push your code repository (including the `server/` directory) to GitHub / GitLab.
2. Log into [Render.com](https://render.com).
3. Click **New +** -> **Blueprint**.
4. Connect your repository. Render will automatically detect `server/render.yaml`.
5. Click **Apply**. Render will deploy the signaling server on the **Free Plan**.

### Option B: Manual Setup on Render
1. Log into [Render.com](https://render.com) and click **New +** -> **Web Service**.
2. Connect your Git repository.
3. Configure the service settings:
   * **Name:** `gamervoice-signaling`
   * **Root Directory:** `server`
   * **Environment:** `Node`
   * **Build Command:** `npm install`
   * **Start Command:** `npm start`
   * **Instance Type:** `Free`
4. Click **Create Web Service**.

Once deployed, your WebSocket URL will be:
`wss://<your-render-service-name>.onrender.com`

---

## 💻 Local Testing

1. Navigate to the `server` directory:
   ```bash
   cd server
   ```
2. Install dependencies:
   ```bash
   npm install
   ```
3. Start the server:
   ```bash
   npm start
   ```
4. Server runs at `ws://localhost:8080`. Health check available at `http://localhost:8080/health`.

---

## 📡 WebSocket Protocol Reference

All messages sent over WebSocket are formatted as JSON objects containing a `"type"` string.

### 1. Create Room
* **Client Request:**
  ```json
  { "type": "create-room" }
  ```
* **Server Response:**
  ```json
  {
    "type": "room-created",
    "roomCode": "A3K9P",
    "peerId": "f47ac10b-58cc-4372-a567-0e02b2c3d479"
  }
  ```

### 2. Join Room
* **Client Request:**
  ```json
  { "type": "join-room", "roomCode": "A3K9P" }
  ```
* **Server Response (to joining client):**
  ```json
  {
    "type": "room-joined",
    "roomCode": "A3K9P",
    "peerId": "e12bc30d-12ab-4567-b890-1e23f456a789",
    "peers": ["f47ac10b-58cc-4372-a567-0e02b2c3d479"]
  }
  ```
* **Server Broadcast (to existing room members):**
  ```json
  {
    "type": "peer-joined",
    "peerId": "e12bc30d-12ab-4567-b890-1e23f456a789"
  }
  ```

### 3. Relay Signals (`offer`, `answer`, `ice-candidate`)
* **Client Request (Sender):**
  ```json
  {
    "type": "offer",
    "targetPeerId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "sdp": "..."
  }
  ```
* **Server Relay (to Target Peer):**
  ```json
  {
    "type": "offer",
    "targetPeerId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "senderPeerId": "e12bc30d-12ab-4567-b890-1e23f456a789",
    "sdp": "..."
  }
  ```

### 4. Leave Room
* **Client Request:**
  ```json
  { "type": "leave-room" }
  ```
* **Server Broadcast (to remaining room members):**
  ```json
  {
    "type": "peer-left",
    "peerId": "e12bc30d-12ab-4567-b890-1e23f456a789"
  }
  ```
