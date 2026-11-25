"""Build step: exports the CLIP image encoder to ONNX and pre-computes the label text embeddings."""
import json, numpy as np, torch, open_clip
from labels import DOC, PHOTO
m, _, _ = open_clip.create_model_and_transforms('ViT-B-32', pretrained='laion2b_s34b_b79k')
m.eval(); tok = open_clip.get_tokenizer('ViT-B-32')
class V(torch.nn.Module):
    def __init__(s, v): super().__init__(); s.v = v
    def forward(s, x): f = s.v(x); return f / f.norm(dim=-1, keepdim=True)
torch.onnx.export(V(m.visual), torch.randn(1, 3, 224, 224), 'model/visual.onnx', input_names=['x'], output_names=['f'],
                  dynamic_axes={'x': {0: 'b'}, 'f': {0: 'b'}}, opset_version=17)
with torch.no_grad():
    t = m.encode_text(tok([f'a photo of {p}' for p in DOC + PHOTO])); t = t / t.norm(dim=-1, keepdim=True)
np.save('model/text.npy', t.numpy().astype(np.float32))
json.dump({'n_doc': len(DOC), 'n_photo': len(PHOTO), 'scale': float(m.logit_scale.exp())}, open('model/meta.json', 'w'))
print('exported', t.shape)
