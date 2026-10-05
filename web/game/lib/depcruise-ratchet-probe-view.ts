// Temporary red probe for the dependency-cruiser ratchet — a view model importing an api client. Reverted in the next commit.
import { fetchGame } from './api';

export const depcruiseRatchetProbe = fetchGame;
