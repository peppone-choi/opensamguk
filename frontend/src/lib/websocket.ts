import { Client } from "@stomp/stompjs";
import SockJS from "sockjs-client";

interface TurnData {
  year: number;
  month: number;
}
interface EventData {
  message?: string;
}

let stompClient: Client | null = null;

export function connectWebSocket(
  worldId: number,
  callbacks: {
    onTurnAdvance?: (data: TurnData) => void;
    onBattle?: (data: EventData) => void;
    onDiplomacy?: (data: EventData) => void;
    onMessage?: (data: EventData) => void;
  },
) {
  if (stompClient?.active) return;

  stompClient = new Client({
    webSocketFactory: () => new SockJS("http://localhost:8080/ws"),
    onConnect: () => {
      if (callbacks.onTurnAdvance) {
        stompClient!.subscribe(`/topic/world/${worldId}/turn`, (msg) => {
          callbacks.onTurnAdvance!(JSON.parse(msg.body));
        });
      }
      if (callbacks.onBattle) {
        stompClient!.subscribe(`/topic/world/${worldId}/battle`, (msg) => {
          callbacks.onBattle!(JSON.parse(msg.body));
        });
      }
      if (callbacks.onDiplomacy) {
        stompClient!.subscribe(`/topic/world/${worldId}/diplomacy`, (msg) => {
          callbacks.onDiplomacy!(JSON.parse(msg.body));
        });
      }
      if (callbacks.onMessage) {
        stompClient!.subscribe(`/topic/world/${worldId}/message`, (msg) => {
          callbacks.onMessage!(JSON.parse(msg.body));
        });
      }
    },
    reconnectDelay: 5000,
  });
  stompClient.activate();
}

export function disconnectWebSocket() {
  stompClient?.deactivate();
  stompClient = null;
}
