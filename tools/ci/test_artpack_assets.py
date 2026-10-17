"""Expose A5's offline artpack tests to local contracts discovery.

Local execution requires Pillow 12.2.0. Hosted CI definitions were retired;
the asset assertions below remain the source of functional verification.
"""

from tools.assets.artpack.tests.test_artpack import ArtpackTests  # noqa: F401
