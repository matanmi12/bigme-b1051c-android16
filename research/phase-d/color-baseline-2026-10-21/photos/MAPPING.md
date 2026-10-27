# Panel photos -> baseline states (2026-10-21, mapped by the time in the file name / EXIF vs states.log)

| state | photo (copy) | original | taken | note |
|---|---|---|---|---|
| S1 Regal + Custom 50/50/50, light off | S1.jpg, S1-2.jpg, **S1-3.jpg** | IMG_20261021_170149 / _170150_1 / _170150_3 | 17:01:49-51 | three shots right after the tap that hid the Gallery bars; S1-3 is the largest file (sharpest) |
| S2 Regal + Default (pipeline off) | S2.jpg | IMG_20261021_170230 | 17:02:30 | state applied 17:02:12 |
| S3 HD 256 (= 177 dither 0 on this GSI) | S3.jpg | IMG_20261021_170557 | 17:05:58 | after the shade open/close frame at ~17:05 |
| S4 | - | - | - | skipped: identical to S3 at the kernel |
| S5 Fast, colour restore off | S5.jpg | IMG_20261021_170659 | 17:06:59 | applied 17:06:17 |
| S6 Fast, colour restore on | S6.jpg | IMG_20261021_170741 | 17:07:42 | applied 17:07:20; **ghost of the QS shade visible** (Fast mode keeps the ghost of the shade I opened to force a frame) |
| S7 = S1 with the front light on (102/210) | S7.jpg | IMG_20261021_170819 | 17:08:19 | applied ~17:08:12 |

Caveats: hand-held phone (framing differs per photo), lamp from above with glare top-left; S5/S6 carry the ghost of the
shade that was opened and closed to produce a real Fast-mode frame. For a cleaner Fast baseline next time: GC16 first
(gc16_req), then the frame change without the shade (the owner's tap on the Gallery).

EXIF (REDMAGIC 9 Pro): exposure mode auto, white balance auto, ISO 500-2119, shutter 1/50-1/25, f/1.88. So the camera
normalised brightness and colour per shot: the photos compare *qualitatively* (saturation, dither texture, ghosting,
text edges), not absolute colour. Next round: Pro mode with ISO, shutter and white balance locked.
Originals: Photos-1-001/ (as transferred). The S*.jpg files are copies named by state.
