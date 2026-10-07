import { Audit } from './audit';
import { FileThumbnailDTO } from './file';

export default interface FloorPlan extends Audit {
  id: number;
  name: string;
  area: number;
  /** The plan drawing (CriticalCopilot writes the floor with its equipment dotted). */
  image?: FileThumbnailDTO | null;
}
