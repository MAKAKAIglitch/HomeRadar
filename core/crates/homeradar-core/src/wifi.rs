use std::collections::BTreeMap;

/// Describes the meaning of a relationship; it does not assert physical identity.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum NetworkRelationKind {
    SameSsid,
    CandidateSameAccessPoint,
    UserConfirmedSameAccessPoint,
    CandidateMeshPeer,
    RouterReportedMeshPeer,
}

/// Groups observation indices by visible SSID for presentation.
///
/// This function deliberately does not infer that BSSIDs with the same SSID
/// belong to the same physical access point or mesh.
pub fn group_indices_by_ssid(ssids: &[Option<String>]) -> BTreeMap<String, Vec<usize>> {
    let mut groups = BTreeMap::new();
    for (index, ssid) in ssids.iter().enumerate() {
        if let Some(name) = ssid.as_ref().filter(|value| !value.is_empty()) {
            groups.entry(name.clone()).or_insert_with(Vec::new).push(index);
        }
    }
    groups
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn same_ssid_is_only_a_display_group() {
        let ssids = vec![
            Some("LabNet".to_string()),
            Some("LabNet".to_string()),
            None,
            Some("Guest".to_string()),
            Some(String::new()),
        ];
        let groups = group_indices_by_ssid(&ssids);
        assert_eq!(groups["LabNet"], vec![0, 1]);
        assert_eq!(groups["Guest"], vec![3]);
        assert_eq!(groups.len(), 2);
    }
}
