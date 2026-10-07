import { Audit } from './audit';

export default interface FloorPlan extends Audit {
  id: number;
  name: string;
  area: number;
  image?: { id: number; name: string; url: string; thumbnailUrl: string | null } | null;
}
